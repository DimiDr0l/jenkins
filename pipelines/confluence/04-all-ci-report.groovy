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
            description: 'Id страницы в конфлюенс с квартальным отчетом. Сохраняется последний использованный!'
        ),
    ])
])

pipeline {
    agent {
        label 'dind'
    }

    triggers {
        cron('00 01 * * *')
    }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '60'))
        ansiColor('xterm')
    }

    stages {
        stage('Get Test It Projects') {
            steps {
                script {
                    if (!params.CONFLUENCE_PAGE_ID) {
                        log.fatal 'Не задан параметр CONFLUENCE_PAGE_ID'
                    }
                    String projectNameSearch = '[ПРОД]'
                    Object projects = tms.searchProjectByName(projectNameSearch)

                    projects.each { project ->
                        Object testPlan = tms.getTestPlansByProjectId(project.id)[0]
                        if (testPlan?.attributes[env.TMS_CI_ID] && testPlan?.attributes[env.TMS_CI_ID].length() > 0) {
                            ciId = testPlan.attributes[env.TMS_CI_ID]
                            testsStatus[ciId] = project.name
                        }
                    }
                }
            }
        }

        stage('Run CI-report job') {
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
                                    log.info 'Report for ' + testsStatus[ciId]
                                    retry(5) {
                                        stage(testsStatus[ciId]) {
                                            build(
                                                job: 'kibchaos/Confluence/CI-report',
                                                wait: true,
                                                propagate: true,
                                                parameters: [
                                                    string(name: 'PROJECT_NAME', value: testsStatus[ciId]),
                                                    string(name: 'PARENT_PAGE_NAME', value: contentPage.title),
                                                ]
                                            )
                                        }
                                        sleep 10
                                    }
                                }
                                else {
                                    log.error "${ciId} from confluence not found in Test IT"
                                }
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

        cleanup {
            cleanWs()
        }
    }
}
