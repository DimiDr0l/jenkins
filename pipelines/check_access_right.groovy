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

PROJECT_SETTINGS_ID = 'chaos-team-qa'
INTERVAL = 3

properties([
    parameters([
        choice(
            name: 'GROUP',
            choices: [
                '',
                'one',
                'two'
            ],
            description: 'Host group in the inventory'
        ),
        string(
            name: 'TAGS',
            defaultValue: 'user_expire,sudoers',
            trim: true,
            description: 'Tags from check_settings.yml, separated by commas'
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
        timestamps()
        timeout(time: 10, unit: 'MINUTES')
        ansiColor('xterm')
    }

    environment {
        PROJECT_SETTINGS_DIR = "${WORKSPACE}/settings"
        PROJECT_DIR = "${PROJECT_SETTINGS_DIR}/${PROJECT_SETTINGS_ID}"
        CONFIG_PATH = "${PROJECT_DIR}/config.yml"
        INVENTORY = "${PROJECT_DIR}/inventory"
        VAULT_FILE = "${PROJECT_DIR}/vault.yml"
        VAULT_PASSWORD = "${PROJECT_DIR}/vault-password-file"
        VAULT_PASS_CREDS = 'kibchaos-vault'
        PRIVATE_KEY = "${PROJECT_DIR}/private_key"
        PRIVATE_KEY_CREDS = 'chaos_key'
        ANSIBLE_CONFIG = "${WORKSPACE}/ansible/ansible.cfg"
    }

    stages {
        stage('Env') {
            steps {
                script {
                    if (!params.GROUP) {
                        log.fatal 'Не выбрана группа хостов'
                    }
                }
            }
        }

        stage('Checkout') {
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    git.repoCheckout(
                        gitUrl: 'placeholder_git_repo/kibchaos.git',
                    )
                    git.repoCheckout(
                        gitUrl: 'placeholder_git_repo/project-settings.git',
                        targetDir: env.PROJECT_SETTINGS_DIR
                    )
                }
            }
        }

        stage('Run Test') {
            steps {
                script {
                    String ansibleCommand = \
                        "ansible-playbook -i ${env.INVENTORY} " +
                        " -l ${params.GROUP} " +
                        " -e @${env.CONFIG_PATH} " +
                        " -e @${env.VAULT_FILE} " +
                        " -e time_between_tests=${INTERVAL} " +
                        " --vault-password-file ${env.VAULT_PASSWORD} " +
                        " -e ansible_ssh_private_key_file=${env.PRIVATE_KEY} " +
                        " --tags ${params.TAGS},manual_start " +
                        "${env.WORKSPACE}/ansible/check_settings.yml "

                    ansibleExecute(
                        command: ansibleCommand
                    )
                }
            }
        }
    }

    post {
        cleanup {
            cleanWs()
        }
    }
}
