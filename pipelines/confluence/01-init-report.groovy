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

properties([
    parameters([
        string(
            name: 'PARENT_PAGE_ID',
            defaultValue: params.PARENT_PAGE_ID ?: '9972256377',
            trim: true,
            description: 'ID родительской страницы'
        ),
        string(
            name: 'PAGE_TITLE_NAME',
            defaultValue: params.PAGE_TITLE_NAME ?: 'Кросс-цель 2023 Q3 статус',
            trim: true,
            description: 'Название страницы'
        ),
        base64File(
            name: 'FILE_CSV',
            description: 'csv file, delimiter semicolon (;)',
        ),
    ])
])

pipeline {
    agent {
        label 'masterLin'
    }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '60'))
        ansiColor('xterm')
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    if (!params.PARENT_PAGE_ID || !params.PAGE_TITLE_NAME) {
                        log.fatal 'Не заданы требуемые параметры'
                    }
                    if (!params.FILE_CSV) {
                        log.fatal 'Не загружен CSV файл!'
                    }
                    bodyPage = confluence.templateTable(
                        'title',
                        [
                            [value: 'Трайб'],
                            [value: 'ID'],
                            [value: 'Наименование АС/ФП из списка цели'],
                            [value: 'Ответственный'],
                            [value: 'Есть данные по КТС'],
                            [value: 'Есть ссылки на мониторинг'],
                            [value: 'Есть доступы Linux'],
                            [value: 'Есть доступы OS'],
                            [value: 'Проект в TestIT готов'],
                            [value: 'Испытания пройдены'],
                            [value: 'Проставлены Passed/Failed'],
                            [value: 'Заведены дефекты'],
                            [value: 'Комментарии'],
                        ]
                    )
                    Object records
                    withFileParameter(name: 'FILE_CSV', allowNoFile: false) {
                        records = readCSV(
                            file: FILE_CSV,
                            format: CSVFormat.DEFAULT.withHeader().withDelimiter(';' as char)
                        )
                    }
                    Object tribes = readYaml(
                        text: bbUtils.getRawFile(path: 'tribes.yaml', repo: 'project-settings')
                    )

                    records.each { project ->
                        String [] categorySplit = project[1].toUpperCase().split()
                        String category = ''
                        if (categorySplit.size() > 1) {
                            category = '<b>' + categorySplit[0][0] + categorySplit[1][0] + '</b> '
                        }
                        else {
                            category = '<b>Category not found</b> '
                        }
                        String parentSystem = ''
                        if (project[6].trim().length() > 1) {
                            parentSystem = '<p><b>Родительская АС:</b> ' + project[6].trim() + '</p>'
                        }
                        String nameDescription = '<div class="content-wrapper"><p>' + category + project[3] + '</p>' + parentSystem + tmplStatusJira(project[0]) + '</div>'

                        String lider = project[5].split()[0]
                        String tribe = ''
                        tribes.findAll { tr ->
                            tr.lider.split()[0].equalsIgnoreCase(lider)
                        }.findResults { tr ->
                            tribe = "<b>${tr.name}</b>"
                        }

                        bodyPage += confluence.templateTable(
                            'table',
                            [
                                [value: tribe],
                                [value: project[0]],
                                [value: nameDescription],
                                [value: project[9]],
                                [value: 'НЕТ', statusLabel: true, color: 'Red'],
                                [value: '-', statusLabel: true],
                                [value: '-', statusLabel: true],
                                [value: '-', statusLabel: true],
                                [value: '-', statusLabel: true],
                                [value: '-', statusLabel: true],
                                [value: '-', statusLabel: true],
                                [value: '-', statusLabel: true],
                                [value: ''],
                            ]
                        )
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
                    log.info 'Проверка создана ли страница'
                    Object searchPages = confluence.getContentByTitle(
                        spaceKey: parentPage.space.key,
                        title: params.PAGE_TITLE_NAME.replaceAll(' ', '%20')
                    )
                    // Если страница нашлась обновляем контент
                    if (searchPages.size > 0) {
                        Object page = searchPages.results[0]
                        pageId = page.id
                        log.info 'Обновляем контент на странице ' + env.CONFLUENCE_URL + '/pages/viewpage.action?pageId=' + pageId
                        confluence.updateContentById(
                            id: pageId,
                            body: bodyPage,
                            title: params.PAGE_TITLE_NAME,
                            versionNumber: page.version.number + 1
                        )
                    }
                    else {
                        Object createPage = confluence.createContent(
                            spaceKey: parentPage.space.key,
                            title: params.PAGE_TITLE_NAME,
                            ancestorsId: parentPage.id,
                            body: bodyPage
                        )
                        pageId = createPage.id
                        log.info 'Создали новую страницу ' + env.CONFLUENCE_URL + '/pages/viewpage.action?pageId=' + pageId
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

void getPopUpMsg() {
    wrap([$class: 'BuildUser']) {
        timeout(time:30, unit:'MINUTES') {
            input message: "Вы ${env.BUILD_USER} абсолютно уверены? таблица перезапишется!!!"
        }
    }
}

String tmplStatusJira(String ciId) {
    String tmpl = ''
    Object tasks = jira.searchProjectByjql(text: ciId)
    if (tasks.total > 0) {
        tmpl = \
            '<p>' +
            '<ac:structured-macro ac:name="jira">' +
            '<ac:parameter ac:name="server">Jira</ac:parameter>' +
            '<ac:parameter ac:name="key">' + tasks.issues[0].key + '</ac:parameter>' +
            '</ac:structured-macro>' +
            '</p>'
    }
    return tmpl
}
