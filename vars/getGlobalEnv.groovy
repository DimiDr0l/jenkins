void call() {
    env.TZ = 'Europe/Moscow'
    env.ANSIBLE_FORCE_COLOR = 'true'

    env.DOCKER_REGISTRY = 'registry.example.ru'
    env.DOCKER_CREDS = 'credentialsid'
    env.CHAOS_IMAGE = "${env.DOCKER_REGISTRY}/ci00354986/ci03311063/chaos-base:master"

    env.BITBUCKET_URL = 'https://bitbucket.example.ru'
    env.BITBUCKET_CREDS = 'credentialsid'
    env.BITBUCKET_PROJECT = 'KIBCHAOS'
    env.BITBUCKET_REPO = 'kibchaos'

    env.CONFLUENCE_URL = 'https://confluence.example.ru'
    env.JIRA_URL = 'https://jira.example.ru'
    env.ATLASSIAN_CREDS = 'bb_devgmorozov_creds'
    env.CONFLUENCE_SPACE_KEY = 'KIBCHAOS'

    env.TMS_API_URL = 'https://testit.example.ru/api/v2'
    env.TMS_AUTH_TOKEN = 'tms_kibchaos_token'
    env.TMS_CI_ID = 'bc435719-57be-4115-8919-0bf6c1f1ffb3'
    env.REGEXP_PATTERN_CI = /(?i)^ci[0-9]+/

    log.info 'Global env load'
}
