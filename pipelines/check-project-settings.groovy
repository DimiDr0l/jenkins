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
LINTER_FAILED = false

properties([
    parameters([
        string(
            name: 'PROJECT_SETTINGS_ID',
            defaultValue: '',
            trim: true,
            description: 'Имя папки с настройками проекта (ожидается в $WORKSPACE/settings).'
        ),
        booleanParam(
            name: 'CHAOSKUBE',
            defaultValue: false,
            description: 'Провести тест возможности использования chaoskube<br>' +
                        '(В целевых проектах будет развернут pod)'
        ),
        string(
            name: 'SETTINGS_BRANCH',
            defaultValue: 'master',
            trim: true,
            description: 'Ветка конфигов'
        ),
        string(
            name: 'TAGS',
            defaultValue: '',
            trim: true,
            description: 'Опционально: Список тэгов тестов (разделитель запятая)'
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
        timeout(time: 30, unit: 'MINUTES')
        ansiColor('xterm')
    }

    environment {
        PROJECT_SETTINGS_DIR = "${WORKSPACE}/settings"
        PROJECT_DIR = "${PROJECT_SETTINGS_DIR}/${params.PROJECT_SETTINGS_ID}"
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
                    if (!params.PROJECT_SETTINGS_ID) {
                        log.fatal 'Не задан проект'
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
                        branch: params.SETTINGS_BRANCH,
                        targetDir: env.PROJECT_SETTINGS_DIR
                    )
                }
            }
        }

        stage('Yaml Lint') {
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    String yamllint = ''
                    try {
                        ansibleExecute(
                            command: "yamllint ${env.PROJECT_DIR} > yamllint.out",
                        )
                    }
                    catch (err) {
                        log.fatal env.LAST_STAGE
                    }
                    finally {
                        yamllint = readFile(file: 'yamllint.out')
                        if (yamllint.size() > 0) {
                            LINTER_FAILED = true
                            String report = "<html><body><h1>Project '${params.PROJECT_SETTINGS_ID}' config files are invalid</h1><code><br>"
                            report += yamllint.replaceAll(/\n/, '\n<br>')
                            report += '</code></body></html>'
                            writeFile(text: report, file: "ansible/yamllint_${params.PROJECT_SETTINGS_ID}.html")
                        }
                    }
                }
            }
        }

        stage('Run Test') {
            steps {
                script {
                    String ansibleCommand = \
                        "ansible-playbook -i ${env.INVENTORY} " +
                        " -e @${env.CONFIG_PATH} " +
                        " -e @${env.VAULT_FILE} " +
                        " ${LINTER_FAILED ? '-e yamllint_report=yamllint.out' : ''} " +
                        " --vault-password-file ${env.VAULT_PASSWORD} " +
                        " -e ansible_ssh_private_key_file=${env.PRIVATE_KEY} " +
                        " ${params.TAGS ? '--tags ' + params.TAGS : ''} " +
                        " ${params.CHAOSKUBE ? '' : '--skip-tags chaoskube'} " +
                        "${env.WORKSPACE}/ansible/check_settings.yml "

                    ansibleExecute(
                        command: ansibleCommand
                    )
                }
            }
        }
    }

    post {
        always {
            script {
                archiveArtifacts(
                    allowEmptyArchive: true,
                    artifacts: 'ansible/*.html',
                )
            }
        }
        cleanup {
            cleanWs()
        }
    }
}
