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

properties([
    parameters([
        string(
            name: 'SPACE_KEY',
            defaultValue: env.CONFLUENCE_SPACE_KEY,
            trim: true,
            description: 'Confluence space key'
        ),
        string(
            name: 'PARENT_PAGE_NAME',
            defaultValue: '',
            trim: true,
            description: 'Опциональный параметр, если отчет создаётся впервые'
        ),
        string(
            name: 'PAGE_TITLE_NAME',
            defaultValue: params.PAGE_TITLE_NAME ?: 'Кросс-цель 2023 Q3 статус',
            trim: true,
            description: 'Опциональный параметр'
        ),
        string(
            name: 'CONFLUENCE_PAGE_ID',
            defaultValue: '',
            trim: true,
            description: 'Опциональный параметр, если заполнен будет использоваться уже созданная страница'
        ),
        base64File(
            name: 'FILE_CSV',
            description: 'csv file, delimiter semicolon (;)',
        ),
    ])
])

pipeline {
    agent {
        label 'dind'
    }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '60'))
        ansiColor('xterm')
    }

    stages {
        stage('ENV') {
            steps {
                script {
                    if (!params.CONFLUENCE_PAGE_ID && !params.PARENT_PAGE_NAME && (!params.SPACE_KEY || !params.PAGE_TITLE_NAME)) {
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
                        String nameDescription = '<p>' + category + project[3] + '</p>' + parentSystem

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
                    // Если указан id страницы, обновляем контент
                    if (params.CONFLUENCE_PAGE_ID) {
                        getPopUpMsg()
                        Object currentPage = confluence.getContentById(id: params.CONFLUENCE_PAGE_ID, expand: 'version')

                        confluence.updateContentById(
                            id: params.CONFLUENCE_PAGE_ID,
                            body: bodyPage,
                            title: currentPage.title,
                            versionNumber: currentPage.version.number + 1
                        )
                    }
                    // Проверем установку параметров
                    else if (params.PAGE_TITLE_NAME && params.PARENT_PAGE_NAME && params.SPACE_KEY) {
                        Object searchPage = confluence.getContentByTitle(
                            spaceKey: params.SPACE_KEY,
                            title: params.PAGE_TITLE_NAME.replaceAll(' ', '%20')
                        )?.results[0]
                        // Если страница нашлась обновляем контент
                        if (searchPage) {
                            getPopUpMsg()
                            confluence.updateContentById(
                                id: searchPage.id,
                                body: bodyPage,
                                title: searchPage.title,
                                versionNumber: searchPage.version.number + 1
                            )
                        }
                        // Если не нашлась ищем родительскую страницу
                        else {
                            Object ancestors = confluence.getContentByTitle(
                                spaceKey: params.SPACE_KEY,
                                title: params.PARENT_PAGE_NAME.replaceAll(' ', '%20')
                            )?.results[0]
                            if (ancestors) {
                                confluence.createContent(
                                    spaceKey: params.SPACE_KEY,
                                    title: params.PAGE_TITLE_NAME,
                                    ancestorsId: ancestors.id,
                                    body: bodyPage
                                )
                            }
                            else {
                                log.fatal "Родительская страница ${params.PARENT_PAGE_NAME} не найдена, проверьте параметры запуска"
                            }
                        }
                    }
                    else {
                        log.fatal 'Не заданы требуемые параметры!'
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
