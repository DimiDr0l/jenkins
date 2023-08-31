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

properties([
    parameters([
        string(
            name: 'TMS_PROJECT_NAME',
            defaultValue: '[ПРОД]',
            trim: true,
            description: 'Название проекта в test it'
        ),
        string(
            name: 'TMS_WEBHOOK_URL',
            defaultValue: "${env.JENKINS_URL}generic-webhook-trigger/invoke",
            trim: true,
            description: 'Jenkins url job trigger'
        ),
        string(
            name: 'TMS_WEBHOOK_TOKEN_CREDS',
            defaultValue: 'devbuildTriggerToken',
            trim: true,
            description: 'ID jenkins credentials'
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
        timeout(time: 60, unit: 'MINUTES')
        ansiColor('xterm')
        timestamps()
    }

    stages {
        stage('Processing') {
            when {
                expression {
                    env.BUILD_ID.toInteger() > 1
                }
            }
            steps {
                script {
                    Object projects = tms.searchProjectByName(params.TMS_PROJECT_NAME)
                    projects.each { project ->
                        Object webhooks = tms.getWebhooksByProjectId(project.id)
                        String projectSettingsId = ''
                        webhooks.each { webhook ->
                            if (webhook.queryParameters.PROJECT_SETTINGS_ID) {
                                projectSettingsId = webhook.queryParameters.PROJECT_SETTINGS_ID
                                log.info "Delete webhook ${webhook.name} in project ${project.name}"
                                tms.deleteWebhookById(webhook.id)
                            }
                        }
                        if (projectSettingsId) {
                            withCredentials([string(credentialsId: params.TMS_WEBHOOK_TOKEN_CREDS, variable: 'WEBHOOK_TOKEN')]) {
                                Map newWebhook = [
                                    name: 'jenkins_webhook',
                                    description: 'jenkins pipeline webhook',
                                    projectId: "${project.id}",
                                    eventType: 'AutomatedTestRunCreated',
                                    url: "${params.TMS_WEBHOOK_URL}",
                                    requestType: 'Post',
                                    isEnabled: 'true',
                                    queryParameters: [
                                        TEST_RUN_ID: '$TEST_RUN_ID',
                                        token: "${WEBHOOK_TOKEN}",
                                        PROJECT_SETTINGS_ID: "${projectSettingsId}"
                                    ]
                                ]
                                tms.createWebhook(newWebhook)
                                log.debug(writeJSON(json: newWebhook, returnText: true))
                            }
                        }
                        else {
                            log.warning "Project ${project.name}: not found webhook parameter PROJECT_SETTINGS_ID"
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
