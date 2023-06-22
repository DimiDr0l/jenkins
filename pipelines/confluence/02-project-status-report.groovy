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
TMS_URL = 'https://testit.example.ru'

// 0 - ошибка проверки, 1 - доступ/актуальность есть, 2 - нет в проекте
Map configStatus = [
    monitoring_check: 2,
    hosts_check: 2,
    os_check: 2,
    testit_project: 0,
    testit_testplan_url: ''
]
String projectName = ''
Boolean openshiftCheck = false
Boolean hostsCheck = false

properties([
    parameters([
        string(
            name: 'PROJECT_SETTINGS_ID',
            defaultValue: '',
            trim: true,
            description: 'Имя папки с настройками проекта (ожидается в $WORKSPACE/settings).'
        ),
        string(
            name: 'CONFLUENCE_PAGE_ID',
            defaultValue: params.CONFLUENCE_PAGE_ID,
            trim: true,
            description: 'ID страницы в конфлюенц'
        ),
        string(
            name: 'SETTINGS_BRANCH',
            defaultValue: 'master',
            trim: true,
            description: 'Ветка конфигов'
        ),
        booleanParam(
            name: 'RUN_POPULATE_JOB',
            defaultValue: false,
            description: 'Создание проекта/тест плана в Test IT'
        ),
        choice(
            name: 'ACTION',
            choices: [
                '',
                'create-project',
                'create-testplan'
            ],
            description: 'Выберите действие'
        ),
    ])
])

pipeline {
    agent {
        label 'dind'
    }

    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '60'))
        ansiColor('xterm')
    }

    stages {
        stage('Check project') {
            steps {
                script {
                    if (!params.PROJECT_SETTINGS_ID || !params.CONFLUENCE_PAGE_ID || !params.SETTINGS_BRANCH) {
                        log.fatal 'Не заданы требуемые параметры'
                    }
                    if (!(params.PROJECT_SETTINGS_ID =~ env.REGEXP_PATTERN_CI)) {
                        log.fatal 'В названии проекта нет CI ID'
                    }
                    Map yamlInventory = readYaml(
                        text: bbUtils.getRawFile(
                            path: params.PROJECT_SETTINGS_ID + '/inventory/inventory.yml',
                            repo: 'project-settings',
                            branch: params.SETTINGS_BRANCH
                        )
                    ).all.children
                    Object yamlConfig = readYaml(
                        text: bbUtils.getRawFile(
                            path: params.PROJECT_SETTINGS_ID + '/config.yml',
                            repo: 'project-settings',
                            branch: params.SETTINGS_BRANCH
                        )
                    )
                    projectName = yamlConfig.tms_project_name
                    if (yamlConfig?.sources) {
                        if (yamlConfig?.sources.grafana) {
                            configStatus.monitoring_check = yamlConfig?.sources.grafana.size() > 0 ? 1 : 2
                        }
                        if (yamlConfig?.sources.reflex && configStatus.monitoring_check != 2) {
                            configStatus.monitoring_check = yamlConfig?.sources.reflex.size() > 0 ? 1 : 2
                        }
                    }

                    yamlInventory.each { group ->
                        if (yamlInventory[group.key]?.hosts) {
                            if (yamlInventory[group.key]?.hosts.find { host -> host.key != '127.0.0.1' }) {
                                hostsCheck = true
                            }
                        }
                        if (yamlInventory[group.key]?.children) {
                            if (yamlInventory[group.key]?.children.find { os -> os.key == 'os_manager' }) {
                                openshiftCheck = true
                            }
                        }
                    }
                    if (params.RUN_POPULATE_JOB) {
                        configStatus.testit_project = 1
                    }
                    else {
                        Object project = tms.searchProjectByName(projectName)
                        if (project) {
                            project = project[0]
                            Object testPlan = tms.getTestPlansByProjectId(project.id)[0]
                            String testPlanCreatedDate = (testPlan.createdDate =~ /[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]/)[0]
                            if (sysUtils.compareCurrentDate(testPlanCreatedDate, 180)) {
                                configStatus.testit_project = 1
                            }
                            else {
                                configStatus.testit_project = 2
                            }
                            configStatus.testit_testplan_url = TMS_URL + "/projects/${project.globalId}/test-plans/${testPlan.globalId}/plan"
                        }
                    }
                }
            }
        }

        stage('Check access') {
            steps {
                script {
                    Map checksStage = [:]
                    Object downstreamJobHostsCheck = [:]
                    Object downstreamJobOpenshiftCheck = [:]
                    if (hostsCheck) {
                        checksStage.hostsCheck = {
                            stage('Hosts Check') {
                                downstreamJobHostsCheck = build(
                                    job: 'kibchaos/check-project-settings',
                                    wait: true, // Ожидать завершения downstream job
                                    propagate: false, // Учитывать результат downstream job
                                    parameters: [
                                        string(name: 'TAGS', value: 'hosts_check'),
                                        string(name: 'SETTINGS_BRANCH', value: params.SETTINGS_BRANCH),
                                        string(name: 'PROJECT_SETTINGS_ID', value: params.PROJECT_SETTINGS_ID),
                                        [$class: 'BooleanParameterValue', name: 'CHAOSKUBE', value: false]
                                    ]
                                )
                                configStatus.hosts_check = downstreamJobHostsCheck.getResult() == 'SUCCESS' ? 1 : 0
                            }
                        }
                    }
                    if (openshiftCheck) {
                        checksStage.openshiftCheck = {
                            stage('Open Shift Check') {
                                downstreamJobOpenshiftCheck = build(
                                    job: 'kibchaos/check-project-settings',
                                    wait: true, // Ожидать завершения downstream job
                                    propagate: false, // Учитывать результат downstream job
                                    parameters: [
                                        string(name: 'TAGS', value: 'openshift_check'),
                                        string(name: 'SETTINGS_BRANCH', value: params.SETTINGS_BRANCH),
                                        string(name: 'PROJECT_SETTINGS_ID', value: params.PROJECT_SETTINGS_ID),
                                        [$class: 'BooleanParameterValue', name: 'CHAOSKUBE', value: true]
                                    ]
                                )
                                configStatus.os_check = downstreamJobOpenshiftCheck.getResult() == 'SUCCESS' ? 1 : 0
                            }
                        }
                    }
                    parallel checksStage
                }
            }
        }

        stage('Testit populate project') {
            when {
                expression {
                    params.RUN_POPULATE_JOB
                }
            }
            steps {
                script {
                    build(
                        job: 'kibchaos/testit-populate-project',
                        wait: true, // Ожидать завершения downstream job
                        propagate: true, // Учитывать результат downstream job
                        parameters: [
                            string(name: 'ACTION', value: params.ACTION),
                            string(name: 'SETTINGS_BRANCH', value: params.SETTINGS_BRANCH),
                            string(name: 'PROJECT_SETTINGS_ID', value: params.PROJECT_SETTINGS_ID),
                        ]
                    )
                    Object project = tms.searchProjectByName(projectName)[0]
                    Object testPlan = tms.getTestPlansByProjectId(project.id)[0]
                    configStatus.testit_testplan_url = TMS_URL + "/projects/${project.globalId}/test-plans/${testPlan.globalId}/plan"
                }
            }
        }

        stage('Confluence publish') {
            steps {
                script {
                    String ciIdParam = (params.PROJECT_SETTINGS_ID =~ env.REGEXP_PATTERN_CI)[0].toUpperCase()
                    Object contentPage = confluence.getContentById(id: params.CONFLUENCE_PAGE_ID)
                    String placeholder = '__ac_placeholder__'
                    String body = contentPage.body.storage.value
                        .replaceAll('&nbsp;', ' ')
                        .replaceAll('ac:', placeholder)
                    Object xml = xmlUtils.parseText(body)
                    Boolean ciFound = false

                    xml.depthFirst().each { tr ->
                        if (tr.name() == 'tr' && tr?.td) {
                            if (tr.td[1].text() =~ env.REGEXP_PATTERN_CI) {
                                String ciId = (tr.td[1].text() =~ env.REGEXP_PATTERN_CI)[0].toUpperCase()
                                if (ciId == ciIdParam) {
                                    ciFound = true
                                    tr?.td[4].div = xmlStatusLabel('ДА', 'Green', placeholder) // Есть данные по КТС (ячейка 5)
                                    // Есть ссылки на мониторинг (ячейка 6)
                                    switch (configStatus.monitoring_check) {
                                        case 1:
                                            tr?.td[5].div = xmlStatusLabel('ДА', 'Green', placeholder)
                                            break
                                        case 2:
                                            tr?.td[5].div = xmlStatusLabel('Нет в проекте', 'Yellow', placeholder)
                                            break
                                        default:
                                            log.fatal 'monitoring_check status error!'
                                            break
                                    }
                                    // Есть доступы линукс (ячейка 7)
                                    switch (configStatus.hosts_check) {
                                        case 0:
                                            tr?.td[6].div = xmlStatusLabel('НЕТ', 'Red', placeholder)
                                            break
                                        case 1:
                                            tr?.td[6].div = xmlStatusLabel('ДА', 'Green', placeholder)
                                            break
                                        case 2:
                                            tr?.td[6].div = xmlStatusLabel('Нет в проекте', 'Yellow', placeholder)
                                            break
                                        default:
                                            log.fatal 'hosts_check status error!'
                                            break
                                    }
                                    // Есть доступы OS (ячейка 8)
                                    switch (configStatus.os_check) {
                                        case 0:
                                            tr?.td[7].div = xmlStatusLabel('НЕТ', 'Red', placeholder)
                                            break
                                        case 1:
                                            tr?.td[7].div = xmlStatusLabel('ДА', 'Green', placeholder)
                                            break
                                        case 2:
                                            tr?.td[7].div = xmlStatusLabel('Нет в проекте', 'Yellow', placeholder)
                                            break
                                        default:
                                            log.fatal 'os_check status error!'
                                            break
                                    }
                                    // Проект в TestIT готов (ячейка 9)
                                    String hrefUrl = '<br><a href="' + configStatus.testit_testplan_url + '">Test Plan</a></br>'
                                    switch (configStatus.testit_project) {
                                        case 0:
                                            tr?.td[8].div = xmlStatusLabel('НЕТ', 'Red', placeholder)
                                            break
                                        case 1:
                                            tr?.td[8].div = xmlStatusLabel('ДА', 'Green', placeholder, hrefUrl)
                                            break
                                        case 2:
                                            tr?.td[8].div = xmlStatusLabel('Просрочен', 'Yellow', placeholder, hrefUrl)
                                            break
                                        default:
                                            log.fatal 'testit_project status error!'
                                            break
                                    }
                                }
                            }
                        }
                    }
                    if (!ciFound) {
                        log.fatal "CI ID ${ciIdParam} not found in confluence"
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

Object xmlStatusLabel(String title, String color, String acPlaceholder, String extraHtml = '') {
    String statusLabel = (
        '<p><ac:structured-macro ac:name="status">' +
        '<ac:parameter ac:name="colour">' + color + '</ac:parameter>' +
        '<ac:parameter ac:name="title">' + title + '</ac:parameter>' +
        '</ac:structured-macro>' + extraHtml + '</p>'
    ).replaceAll('ac:', acPlaceholder)

    return xmlUtils.parseText(statusLabel)
}
