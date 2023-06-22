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
COMMAND_PLAY = ''
CI_VAL = ''
REGEXP_PATTERN_CI = /(?i)^ci[0-9]+/

properties([
    parameters([
        choice(
            name: 'ACTION',
            choices: [
                '',
                'create-project',
                'create-testplan'
            ],
            description: 'Выберите действие:<br>' +
                '<b>create-project</b><br>' +
                'В Test It будет создан проект сменем указанным в значении параметра tms_project_name, в настройках проекта.<br>' +
                'В случае, если существует активный проект с таким именем, никакие изменения не будут сделаны.<br>' +
                '!!! Учетная запись, токен которой используется в конфигурации, должна иметь привилегии позволяющие создать проект.<br>' +
                '<b>create-testplan</b><br>' +
                'В существующем проекте Test It, который использует конфигурацию указанную в параметре PROJECT_SETTINGS_ID<br>' +
                'будет создан тест-план с названием "Деструктивные испытания dd-mm-yyyy" с текущей датой.<br>' +
                'Перед запуском убедитесь, что не существует тестплана с таким именем.'
        ),
        string(
            name: 'PROJECT_SETTINGS_ID',
            defaultValue: '',
            trim: true,
            description: \
                'Имя папки с настройками проекта (ожидается в $WORKSPACE/settings).<br>' +
                'Для создания тест плана в проекте TestIT будет использован файл инвентаря ansible.<br>' +
                'Это параметр будет переопределен в случае использования загружаемого файла с параметрами.'
        ),
        string(
            name: 'SETTINGS_REPO',
            defaultValue: 'placeholder_git_repo/project-settings.git',
            trim: true,
            description: 'Репозиторий конфигов'
        ),
        string(
            name: 'SETTINGS_BRANCH',
            defaultValue: 'master',
            trim: true,
            description: 'Ветка конфигов'
        ),
        base64File(
            name: 'TMS_PROJECT_CONFIG',
            description: \
                '<b>Опционально. При выборе этого параметра остальные игнорируются.</b><br>' +
                'Вы можете использовать самостоятельно созданный конфигурационный файл для проекта TestIt.<br>'
        )
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
        TMS_WEBHOOK_TOKEN_CREDS = 'devbuildTriggerToken'
        PROJECT_SETTINGS_DIR = "${WORKSPACE}/settings"
        PROJECT_DIR = "${PROJECT_SETTINGS_DIR}/${params.PROJECT_SETTINGS_ID}"
        CONFIG_PATH = "${PROJECT_DIR}/config.yml"
        INVENTORY = "${PROJECT_DIR}/inventory"
        VAULT_FILE = "${PROJECT_DIR}/vault.yml"
        VAULT_PASSWORD = "${PROJECT_DIR}/vault-password-file"
        VAULT_PASS_CREDS = 'kibchaos-vault'

        PLAY_EXTRAS = \
            "ansible-playbook -i ${INVENTORY} " +
            " -e @${CONFIG_PATH} " +
            " -e @${VAULT_FILE} " +
            " --vault-password-file ${VAULT_PASSWORD} " +
            "${WORKSPACE}/ansible/tms_populate_project.yml "
    }

    stages {
        stage('Env') {
            steps {
                script {
                    if ((!params.ACTION || !params.PROJECT_SETTINGS_ID) & !params.TMS_PROJECT_CONFIG) {
                        log.fatal \
                            'Не задан один из параметров:\n' +
                            "ACTION: ${params.ACTION}\n" +
                            "PROJECT_SETTINGS_ID: ${params.PROJECT_SETTINGS_ID}"
                    }
                    if (params.PROJECT_SETTINGS_ID =~ REGEXP_PATTERN_CI) {
                        CI_VAL = (params.PROJECT_SETTINGS_ID =~ REGEXP_PATTERN_CI)[0].toUpperCase()
                    }
                    else {
                        log.fatal 'Не совпадение маски названия проекта ci[0-9]+.*'
                    }

                    if (params.TMS_PROJECT_CONFIG) {
                        withFileParameter(name: 'TMS_PROJECT_CONFIG', allowNoFile: true) {
                            withCredentials([string(credentialsId: env.TMS_AUTH_TOKEN, variable: 'TOKEN')]) {
                                sh 'cp $TMS_PROJECT_CONFIG tms_project_config.yml'
                                COMMAND_PLAY = 'ansible-playbook -i localhost, ' +
                                    ' -e @tms_project_config.yml ' +
                                    " -e tms_token=${env.TOKEN} " +
                                    ' ansible/tms_populate_project.yml'
                            }
                        }
                    }
                    else if (params.ACTION == 'create-testplan') {
                        COMMAND_PLAY = env.PLAY_EXTRAS +
                            ' -e tms_create_testplan_only=true ' +
                            " -e tms_testplan_attributes_ci=${CI_VAL}"
                    }
                    else if (params.ACTION == 'create-project') {
                        withCredentials([string(credentialsId: env.TMS_WEBHOOK_TOKEN_CREDS, variable: 'WEBHOOK_TOKEN')]) {
                            COMMAND_PLAY = env.PLAY_EXTRAS +
                                ' -e tms_create_project=true ' +
                                " -e tms_project_settings_id=${params.PROJECT_SETTINGS_ID} " +
                                " -e tms_webhook_token=${WEBHOOK_TOKEN} " +
                                " -e tms_testplan_attributes_ci=${CI_VAL} "
                        }
                    }
                    else {
                        log.fatal 'Некорректный параметр ACTION'
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
                        gitUrl: params.SETTINGS_REPO,
                        targetDir: env.PROJECT_SETTINGS_DIR,
                        branch: params.SETTINGS_BRANCH,
                    )
                }
            }
        }

        stage('Populate project') {
            steps {
                script {
                    wrap([$class: 'MaskPasswordsBuildWrapper', varMaskRegexes: [[regex: 'tms_webhook_token.*']]]) {
                        ansibleExecute(
                            command: COMMAND_PLAY
                        )
                    }
                }
            }
        }
    }
}
