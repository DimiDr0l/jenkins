#!groovy

library(
    identifier: 'shared_lib@master',
    changelog: false,
    retriever: modernSCM(
        scm: [
            $class: 'GitSCMSource',
            remote: 'placeholder_git_lib_repo',
            credentialsId: 'credentialsid',
        ],
    )
)
getGlobalEnv()
Map testsStatus = [:]

properties([
    parameters([
        string(
            name: 'CONFLUENCE_PAGE_ID',
            defaultValue: params.CONFLUENCE_PAGE_ID ?: '10381594382',
            trim: true,
            description: 'Id страницы в конфлюенс. Сохраняется последний использованный!'
        ),
    ])
])

pipeline {
    agent {
        label 'dind'
    }

    triggers {
        // parameterizedCron("00 07 * * * %CONFLUENCE_PAGE_ID=${params.CONFLUENCE_PAGE_ID};")
        cron('00 06 * * *')
    }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '60'))
        ansiColor('xterm')
    }

    stages {
        stage('Get Test It Analytics') {
            steps {
                script {
                    if (!params.CONFLUENCE_PAGE_ID) {
                        log.fatal 'Не задан параметр CONFLUENCE_PAGE_ID'
                    }
                    String projectNameSearch = '[ПРОД]'
                    Object projects = tms.searchProjectByName(projectNameSearch)

                    projects.each { project ->
                        String projectName = project.name.replaceAll(/\[ПРОД\]\s*/, '')
                        Object testPlan = tms.getTestPlansByProjectId(project.id)[0]
                        Object analitics = tms.getAnalyticsByTestPlanId(testPlan.id).countGroupByStatus
                        if (testPlan?.attributes[env.TMS_CI_ID] && testPlan?.attributes[env.TMS_CI_ID].length() > 0) {
                            ciId = testPlan.attributes[env.TMS_CI_ID]
                            Map testStatus = [
                                passed: 0,
                                failed: 0,
                                skipped: 0,
                                inprogress: 0,
                                blocked: 0,
                                noresults: 0,
                            ]

                            Integer testTotal = 0
                            testStatus.each { stat ->
                                analitics.findAll { astat ->
                                    stat.key.equalsIgnoreCase(astat.status)
                                }.findResults { val ->
                                    testStatus[stat.key] = val.value
                                    testTotal += val.value
                                }
                            }

                            // Ищем в последнем тест ране ссылки на jira
                            testStatus.testtotal = testTotal
                            testStatus.jiralinks = []
                            Integer totalJiraLinks = 0
                            Object lastResults = tms.getLastResultsByTestPlanId(testPlan.id)
                            lastResults.findAll { lastResult ->
                                lastResult.status.equalsIgnoreCase('Failed')
                            }.findResults { lastResult ->
                                lastResult.lastTestResult.links.findAll { links ->
                                    links.type.toString().equalsIgnoreCase('Defect')
                                }.findResults { links ->
                                    totalJiraLinks++
                                    testStatus.jiralinks += links.url
                                }
                            }
                            testStatus.jiralinks = testStatus.jiralinks.unique()
                            testStatus.totaljiralinks = totalJiraLinks

                            // Подсчёт %
                            Integer percentComplete = (
                                testStatus.passed +
                                testStatus.inprogress +
                                testStatus.blocked +
                                testStatus.failed
                            ) / testTotal * 100

                            // Если тест план заблокирован, все тесты прошли, заведены дефекты то выставляем 101
                            if (testPlan.status.equalsIgnoreCase('Completed') \
                                    && testStatus.passed + testStatus.failed == testStatus.testtotal \
                                    && testStatus.failed == testStatus.totaljiralinks) {
                                log.info "Project ${projectName} is completed"
                                percentComplete = 101
                            }
                            testStatus.percentcomplete = percentComplete
                            testStatus.project = projectName
                            testsStatus[ciId] = testStatus
                        }
                    }
                }
            }
        }

        stage('Confluence publish') {
            steps {
                script {
                    Object contentPage = confluence.getContentById(id: params.CONFLUENCE_PAGE_ID)
                    String placeholder = '__ac_placeholder__'
                    String body = contentPage.body.storage.value
                        .replaceAll('&nbsp;', ' ')
                        .replaceAll('ac:', placeholder)
                    Object xml = xmlUtils.parseText(body)

                    xml.depthFirst().each { tr ->
                        if (tr.name() == 'tr' && tr?.td) {
                            if (tr.td[1].text() =~ env.REGEXP_PATTERN_CI) {
                                String ciId = (tr.td[1].text() =~ env.REGEXP_PATTERN_CI)[0].toUpperCase()
                                if (testsStatus[ciId]) {
                                    String colorCell = 'Grey'
                                    Object testStatus = testsStatus[ciId]
                                    Integer passed = testStatus.passed
                                    Integer failed = testStatus.failed
                                    // List jiralinks = testStatus.jiralinks // пока не используется
                                    Integer testtotal = testStatus.testtotal
                                    Integer totaljiralinks = testStatus.totaljiralinks
                                    Integer percentComplete = testStatus.percentcomplete
                                    if (percentComplete >= 100) {
                                        colorCell = 'Green'
                                    }
                                    // Колонка "Испытания пройдены", кол-во выполненных тестов в % (ячейка 10)
                                    tr?.td[9].div = xmlStatusLabel(
                                        percentComplete > 100 ? 'ДА' : percentComplete.toString() + '%', colorCell, placeholder
                                    )
                                    // Колонка "Проставлены Passed/Failed", если все тесты завершены (ячейка 11)
                                    if (passed + failed == testtotal) {
                                        tr?.td[10].div = xmlStatusLabel('ДА', 'Green', placeholder)
                                        // Колонка "Заведены дефекты", проверка на jira ссылки (ячейка 12)
                                        if (failed <= totaljiralinks) {
                                            tr?.td[11].div = xmlStatusLabel('ДА', 'Green', placeholder)
                                        }
                                        else if (totaljiralinks > 0) {
                                            Integer percentJiraLinks = totaljiralinks / failed * 100
                                            tr?.td[11].div = xmlStatusLabel(percentJiraLinks + '%', 'Grey', placeholder)
                                        }
                                        else {
                                            tr?.td[11].div = xmlStatusLabel('НЕТ', 'Red', placeholder)
                                        }
                                    }
                                    else {
                                        Integer percentPassed = (passed + failed) / testtotal * 100
                                        if (percentPassed > 0) {
                                            tr?.td[10].div = xmlStatusLabel(percentPassed + '%', 'Grey', placeholder)
                                        }
                                        else {
                                            tr?.td[10].div = xmlStatusLabel('НЕТ', 'Red', placeholder)
                                        }
                                        tr?.td[11].div = xmlStatusLabel('НЕТ', 'Red', placeholder)
                                    }
                                }
                                else {
                                    // Если проект не найден в Test It (ячейки 10-12)
                                    tr?.td[9].div = xmlStatusLabel('НЕТ', 'Red', placeholder)
                                    tr?.td[10].div = xmlStatusLabel('НЕТ', 'Red', placeholder)
                                    tr?.td[11].div = xmlStatusLabel('НЕТ', 'Red', placeholder)
                                    log.error "${ciId} from confluence not found in Test IT"
                                }
                            }
                        }
                    }
                    // Удаляем 1 строку в сформированом html, т.к. сериализатор добавляет xml тег
                    body = xmlUtils.serialize(xml)
                        .split('\n')
                        .drop(1)
                        .join('\n')
                        .replaceAll(placeholder, 'ac:')

                    Integer iterVersionNumber = contentPage.version.number + 1

                    confluence.updateContentById(
                        id: params.CONFLUENCE_PAGE_ID,
                        body: body,
                        title: contentPage.title,
                        versionNumber: iterVersionNumber
                    )
                }
            }
        }
    }

    post {
        always {
            script {
                log.info 'Done ' + currentBuild.result
            }
        }

        cleanup {
            cleanWs()
        }
    }
}

Object xmlStatusLabel(String title, String color, String acPlaceholder) {
    String statusLabel = (
        '<p><ac:structured-macro ac:name="status">' +
        '<ac:parameter ac:name="colour">' + color + '</ac:parameter>' +
        '<ac:parameter ac:name="title">' + title + '</ac:parameter>' +
        '</ac:structured-macro></p>'
    ).replaceAll('ac:', acPlaceholder)

    return xmlUtils.parseText(statusLabel)
}
