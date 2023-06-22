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
            name: 'SPACE_KEY',
            defaultValue: env.CONFLUENCE_SPACE_KEY,
            trim: true,
            description: 'Confluence space key'
        ),
        string(
            name: 'PARENT_PAGE_NAME',
            defaultValue: params.PARENT_PAGE_NAME,
            trim: true,
            description: 'Название родительской страницы'
        ),
    ])
])

pipeline {
    agent {
        label 'docker'
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
                    if (!params.PARENT_PAGE_NAME || !params.SPACE_KEY || !params.PROJECT_NAME) {
                        log.fatal 'Не заданы требуемые параметры'
                    }
                    Map testStatus = [
                        passed: 0,
                        failed: 0,
                        skipped: 0,
                        inprogress: 0,
                        blocked: 0,
                        noresults: 0,
                    ]
                    bodyPage += '<h3 class="container__title h3" style="text-decoration: none;">РАСПРЕДЕЛЕНИЕ ТЕСТОВ ПО РЕЗУЛЬТАТАМ</h3>'

                    Object project = tms.searchProjectByName(params.PROJECT_NAME)
                    if (project) {
                        project = project[0]
                        Object testPlan = tms.getTestPlansByProjectId(project.id)[0]

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
                        bodyPage += '<h3 class="container__title h3" style="text-decoration: none;">ДЕТАЛИЗАЦИЯ ТЕСТОВ</h3>'

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
                        String projectName = params.PROJECT_NAME.replaceAll(/\[ПРОД\]\s*/, '')
                        pageTitle = projectName + '_' + testPlanCreatedDate

                        Object lastResults = tms.getLastResultsByTestPlanId(testPlan.id)
                        lastResults.each { test ->
                            log.info 'TEST ' + test.workItemName
                            String jiralinks = ''
                            String screenshots = ''
                            String logs = ''
                            String comment = ''
                            if ((test?.lastTestResult).toString() != 'null') {
                                log.info 'Get "Defect" links by test'
                                comment = test.lastTestResult.comment
                                test.lastTestResult.links.findAll { link ->
                                    link.type.toString().equalsIgnoreCase('Defect')
                                }.findResults { link ->
                                    jiralinks += tmplStatusJira(link.url)
                                }

                                log.info 'Download attachments'
                                test.lastTestResult.attachments.each { attachment ->
                                    if (attachment.size > 0) {
                                        if (attachment.name =~ /(?i)\.png$|\.jpg$/) {
                                            screenshots += tmplAttachFile(attachment.name)
                                        }
                                        else {
                                            logs += tmplAttachFile(attachment.name, 'file')
                                        }
                                        dir('attachments') {
                                            tms.downloadAttachmentById(attachment.id, attachment.name)
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
                    Object searchPage = confluence.getContentByTitle(
                        spaceKey: params.SPACE_KEY,
                        title: pageTitle.replaceAll(' ', '%20')
                    )?.results[0]
                    // Если страница нашлась обновляем контент
                    String pageId = ''
                    List attachments = []
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
                    // Если не нашлась ищем родительскую страницу
                    else {
                        Object ancestors = confluence.getContentByTitle(
                            spaceKey: params.SPACE_KEY,
                            title: params.PARENT_PAGE_NAME.replaceAll(' ', '%20')
                        )?.results[0]
                        if (ancestors) {
                            Object createPage = confluence.createContent(
                                spaceKey: params.SPACE_KEY,
                                title: pageTitle,
                                ancestorsId: ancestors.id,
                                body: bodyPage
                            )
                            pageId = createPage.id
                            log.info 'Создаём новую страницу ' + env.CONFLUENCE_URL + '/pages/viewpage.action?pageId=' + pageId
                        }
                        else {
                            log.fatal "Родительская страница ${params.PARENT_PAGE_NAME} не найдена, проверьте параметры запуска"
                        }
                    }

                    dir('attachments') {
                        log. info 'Upload attachments'
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
