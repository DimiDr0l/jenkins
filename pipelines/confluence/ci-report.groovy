#!groovy

String bbCreds = env.JENKINS_URL =~ /(?i)qa-jenkins.ru/ ? '0fd7f3e0-957e-4e3a-8e3b-b383d7af9d8a' : 'git_creds'

library(
    identifier: 'shared_lib@master',
    changelog: false,
    retriever: modernSCM(
        scm: [
            $class: 'GitSCMSource',
            remote: 'https://github.com/DimiDr0l/jenkins.git',
            credentialsId: bbCreds,
        ],
    )
)

getGlobalEnv()
String bodyPage = ''
String pageTitle = ''

properties([
    parameters([
        string(
            name: 'PROJECT_NAME',
            defaultValue: '',
            trim: true,
            description: 'Название проекта в Test IT'
        ),
        string(
            name: 'PARENT_PAGE_ID',
            defaultValue: '',
            trim: true,
            description: 'ID родительской страницы'
        ),
    ])
])

pipeline {
    agent {
        label 'masterLin'
    }

    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '60'))
        ansiColor('xterm')
    }

    stages {
        stage('Download Test IT artifacts') {
            steps {
                script {
                    if (!params.PARENT_PAGE_ID || !params.PROJECT_NAME) {
                        log.fatal 'Не заданы требуемые параметры'
                    }
                    String projectName = params.PROJECT_NAME.replaceAll(/\[ПРОД\]\s*/, '')
                    Map testStatus = [
                        passed: 0,
                        failed: 0,
                        skipped: 0,
                        inprogress: 0,
                        blocked: 0,
                        noresults: 0,
                    ]

                    Object project = tms.searchProjectByName(params.PROJECT_NAME)
                    if (project) {
                        project = project[0]
                        Object testPlan = tms.getTestPlansByProjectId(projectId: project.id)[0]

                        log.info 'ОБЩИЕ СВЕДЕНИЯ'
                        bodyPage += '<h3>ОБЩИЕ СВЕДЕНИЯ</h3>'
                        bodyPage += confluence.templateTable(
                            'title',
                            [
                                [value: 'КЭ'],
                                [value: 'Наименование'],
                                [value: 'Ссылка на тест план'],
                            ]
                        )
                        String ciId = ''
                        if (testPlan?.attributes && testPlan?.attributes[env.TMS_ATTRIBUTE_CI_ID]) {
                            ciId = testPlan.attributes[env.TMS_ATTRIBUTE_CI_ID]
                        }
                        String testPlanUrl = \
                            '<a href="' +
                            env.TMS_URL + "/projects/${project.globalId}/test-plans/${testPlan.globalId}/plan" +
                            '">Test Plan</a>'
                        bodyPage += confluence.templateTable(
                            'table',
                            [
                                [value: ciId],
                                [value: projectName],
                                [value: testPlanUrl],
                            ]
                        )
                        bodyPage += confluence.templateTable('end')
                        bodyPage += '<h3>РАСПРЕДЕЛЕНИЕ ТЕСТОВ ПО РЕЗУЛЬТАТАМ</h3>'

                        log.info 'Get analitics'
                        Object analitics = tms.getAnalyticsByTestPlanId(testPlan.id).countGroupByStatus
                        testStatus.each { stat ->
                                analitics.findAll { astat ->
                                    stat.key.equalsIgnoreCase(astat.status)
                                }.findResults { val ->
                                    testStatus[stat.key] = val.value
                                }
                            }
                        bodyPage += tmplChart(
                            testStatus.passed,
                            testStatus.failed,
                            testStatus.noresults,
                            testStatus.blocked,
                            testStatus.skipped,
                            testStatus.inprogress
                        )
                        bodyPage += '<h3>ДЕТАЛИЗАЦИЯ ТЕСТОВ</h3>'

                        bodyPage += confluence.templateTable(
                            'title',
                            [
                                [value: 'Группа серверов'],
                                [value: 'Название теста'],
                                [value: 'Статус'],
                                [value: 'Скриншоты'],
                                [value: 'Логи'],
                                [value: 'Комментарий'],
                                [value: 'Дефект'],
                            ]
                        )
                        Map servers = [:]
                        Object testPlanConfiguration = tms.getTestPlanConfiguration(testPlan.id)
                        testPlanConfiguration.each { server ->
                            servers[server.id] = server.name
                        }

                        String testPlanCreatedDate = (testPlan.createdDate =~ /[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]/)[0]
                        pageTitle = projectName + '_' + testPlanCreatedDate

                        Object lastResults = tms.getLastResultsByTestPlanId(testPlan.id)
                        lastResults.each { test ->
                            log.info 'TEST ' + test.workItemName
                            String jiralinks = ''
                            String screenshots = ''
                            String logs = ''
                            String comment = ''
                            if ((test?.lastTestResult).toString() != 'null') {
                                log.info 'Получение дефектных ссылок'
                                comment = test.lastTestResult.comment
                                comment = comment.trim() == 'null' ? '' : comment
                                comment = comment.replaceAll('<', '&lt;').replaceAll('>', '&gt;')
                                test.lastTestResult.links.findAll { link ->
                                    link.type.toString().equalsIgnoreCase('Defect')
                                }.findResults { link ->
                                    jiralinks += tmplStatusJira(link.url)
                                }

                                log.info 'Скачивание артефактов'
                                test.lastTestResult.attachments.each { attachment ->
                                    if (attachment.size > 0) {
                                        String fileName = attachment.name.split('\\.')[0]
                                        String prefixName = attachment.name.split('\\.')[1]
                                        String resultFile = fileName + '_' + attachment.id + '.' + prefixName
                                        if (attachment.name =~ /(?i)\.png$|\.jpg$/) {
                                            screenshots += tmplAttachFile(resultFile)
                                        }
                                        else {
                                            logs += tmplAttachFile(resultFile, 'file')
                                        }
                                        dir('attachments') {
                                            tms.downloadAttachmentById(attachment.id, resultFile)
                                        }
                                    }
                                }
                                if (screenshots) {
                                    screenshots = \
                                        '<div class="content-wrapper">' +
                                        '<ac:structured-macro ac:name="expand">' +
                                        '<ac:parameter ac:name="title">Скриншоты</ac:parameter>' +
                                        '<ac:rich-text-body>' +
                                        screenshots +
                                        '</ac:rich-text-body></ac:structured-macro></div>'
                                }
                                if (logs) {
                                    logs = '<div class="content-wrapper">' + logs + '</div>'
                                }
                            }

                            bodyPage += confluence.templateTable(
                                'table',
                                [
                                    [value: servers[test.configurationId]],
                                    [value: test.workItemName],
                                    [value: tmplStatusLabel(test.status)],
                                    [value: screenshots],
                                    [value: logs],
                                    [value: comment],
                                    [value: jiralinks],
                                ]
                            )
                        }
                    }
                    else {
                        log.fatal "Проект ${params.PROJECT_NAME} в Test IT не найден"
                    }
                    bodyPage += confluence.templateTable('end')
                }
            }
        }

        stage('Confluence publish') {
            steps {
                script {
                    log.info 'Получение родительской страницы'
                    Object parentPage = confluence.getContentById(id: params.PARENT_PAGE_ID, expand: '')
                    String pageId = ''
                    List attachments = []
                    log.info 'Проверка создана ли страница'
                    Object searchPage = confluence.getContentByTitle(
                        spaceKey: parentPage.space.key,
                        title: pageTitle.replaceAll(' ', '%20')
                    )?.results[0]
                    // Если страница нашлась обновляем контент
                    if (searchPage) {
                        pageId = searchPage.id
                        log.info 'Обновляем контент на странице ' + env.CONFLUENCE_URL + '/pages/viewpage.action?pageId=' + pageId
                        confluence.updateContentById(
                            id: pageId,
                            body: bodyPage,
                            title: pageTitle,
                            versionNumber: searchPage.version.number + 1
                        )
                        Object currentAttachments = confluence.attachment(contentId: pageId)
                        attachments = currentAttachments.results.collect { attach -> [title: attach.title, id: attach.id] }
                        Integer i = 0
                        while (currentAttachments.size >= 100) {
                            i++
                            currentAttachments = confluence.attachment(contentId: pageId, start: i * 100)
                            attachments += currentAttachments.results.collect { attach -> [title: attach.title, id: attach.id] }
                        }
                    }
                    else {
                        log.info 'Создаём новую страницу'
                        Object createPage = confluence.createContent(
                            spaceKey: parentPage.space.key,
                            title: pageTitle,
                            ancestorsId: parentPage.id,
                            body: bodyPage
                        )
                        pageId = createPage.id
                        log.info 'Создали новую страницу ' + env.CONFLUENCE_URL + '/pages/viewpage.action?pageId=' + pageId
                    }

                    if (fileExists('attachments/')) {
                        dir('attachments') {
                            log. info 'Загрузка артефактов'
                            Object files = findFiles(glob: '*')
                            files.each { file ->
                                String attachId = ''
                                if (attachments.findAll { at -> at.title =~ file.name }.findResult { at -> attachId = at.id }) {
                                    confluence.deleteContentById(id: attachId)
                                }
                                confluence.attachment(action: 'create', contentId: pageId, file: file.path)
                            }
                        }
                    }
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
        failure {
            script {
                log.debug bodyPage
            }
        }

        cleanup {
            cleanWs()
        }
    }
}

String tmplAttachFile(String fileName, String type = 'image') {
    String fileTemplate = ''
    if (type == 'link') {
        fileTemplate = \
            '<ac:link>' +
            '<ri:attachment ri:filename="' + fileName + '"/>' +
            '</ac:link>'
    }
    else if (type == 'file') {
        fileTemplate = \
            '<ac:structured-macro ac:name="view-file">' +
            '<ac:parameter ac:name="name">' +
            '<ri:attachment ri:filename="' + fileName + '"/>' +
            '</ac:parameter>' +
            '<ac:parameter ac:name="height">150</ac:parameter>' +
            '</ac:structured-macro>'
    }
    else {
        fileTemplate = \
            '<ac:image ac:thumbnail="true" ac:width="200">' +
            '<ri:attachment ri:filename="' + fileName + '"/>' +
            '</ac:image>'
    }
    return fileTemplate
}

String tmplStatusLabel(String status) {
    String color = ''
    switch (status) {
        case 'Passed':
            color = 'Green'
            break
        case 'Failed':
            color = 'Red'
            break
        case 'Skipped':
            color = 'Yellow'
            break
        default:
            color = 'Grey'
            break
    }
    return (
        '<div class="content-wrapper">' +
        '<p><ac:structured-macro ac:name="status">' +
        '<ac:parameter ac:name="colour">' + color + '</ac:parameter>' +
        '<ac:parameter ac:name="title">' + status + '</ac:parameter>' +
        '</ac:structured-macro></p>' +
        '</div>'
    )
}

String tmplStatusJira(String jiraUrl) {
    String task = jiraUrl.split('/')[-1]
    String tmpl = ''
    if (jiraUrl.find('jira')) {
        tmpl = (
            '<div class="content-wrapper">' +
            '<ac:structured-macro ac:name="jira">' +
            '<ac:parameter ac:name="server">Jira</ac:parameter>' +
            '<ac:parameter ac:name="key">' + task + '</ac:parameter>' +
            '</ac:structured-macro>' +
            '</div>'
        )
    }
    else {
        tmpl = (
            '<a href="' + jiraUrl + '">' + task + '</a>'
        )
    }
    return tmpl
}

String tmplChart(int passed, int failed, int noresults, int blocked, int skipped, int inprogress) {
    return (
        '<ac:structured-macro ac:name="table-chart">' +
        '<ac:parameter ac:name="barColoringType">mono</ac:parameter>' +
        '<ac:parameter ac:name="tfc-width">450</ac:parameter>' +
        '<ac:parameter ac:name="hidecontrols">true</ac:parameter>' +
        '<ac:parameter ac:name="dataorientation">Horizontal</ac:parameter>' +
        '<ac:parameter ac:name="column">Статус</ac:parameter>' +
        '<ac:parameter ac:name="aggregation">Значение</ac:parameter>' +
        '<ac:parameter ac:name="is3d">false</ac:parameter>' +
        '<ac:parameter ac:name="align">Left</ac:parameter>' +
        '<ac:parameter ac:name="version">3</ac:parameter>' +
        '<ac:parameter ac:name="colors">#8eb021,#d04437,#3572b0,#f6c342,#654982,#f691b2,#8eb021</ac:parameter>' +
        '<ac:parameter ac:name="hide">true</ac:parameter>' +
        '<ac:parameter ac:name="isFirstTimeEnter">false</ac:parameter>' +
        '<ac:parameter ac:name="tfc-height">450</ac:parameter>' +
        '<ac:parameter ac:name="pieKeys">Успешен‚Провален‚Ожидает‚Заблокирован‚Пропущен‚В процессе,12</ac:parameter>' +
        '<ac:parameter ac:name="formatVersion">3</ac:parameter>' +
        '<ac:rich-text-body>' +
        '<table class="wrapped">' +
        '<tbody>' +
        '<tr>' +
        '<th>Статус</th>' +
        '<th>Успешен</th>' +
        '<th>Провален</th>' +
        '<th>Ожидает</th>' +
        '<th>Заблокирован</th>' +
        '<th>Пропущен</th>' +
        '<th>В процессе</th>' +
        '</tr>' +
        '<tr>' +
        '<td>Значение</td>' +
        '<td>' + passed +'</td>' +
        '<td>' + failed + '</td>' +
        '<td>' + noresults + '</td>' +
        '<td>' + blocked + '</td>' +
        '<td>' + skipped + '</td>' +
        '<td>' + inprogress +'</td>' +
        '</tr>' +
        '</tbody>' +
        '</table>' +
        '</ac:rich-text-body>' +
        '</ac:structured-macro>'
    )
}
