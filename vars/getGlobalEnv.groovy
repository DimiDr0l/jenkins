#!groovy

void call() {
    JENKINS_QA = /(?i)qa-jenkins.ru/

    env.HTTP_REQUEST_QUIET = 'true'

    env.TZ = 'Europe/Moscow'
    env.ANSIBLE_FORCE_COLOR = 'true'
    env.PY_COLORS = '1'

    env.PRIVATE_KEY_CREDS = 'chaos_key'

    env.DOCKER_REGISTRY = 'docker-registry.ru'
    env.CHAOS_IMAGE = "${env.DOCKER_REGISTRY}/chaos-base:master"

    env.KUBERNETES_CLOUD = 'kubernetes'
    env.KUBERNETES_JNLP_IMAGE = 'docker-registry.ru/jenkins_agent_ci:1.98.24'

    env.BITBUCKET_URL = 'https://github.com'
    env.BITBUCKET_PROJECT = 'creds'
    env.BITBUCKET_REPO = 'creds'

    env.CONFLUENCE_URL = 'https://confluence.ru'
    env.JIRA_URL = 'https://jira.ru'
    env.ATLASSIAN_CREDS = 'atlassian creds'
    env.CONFLUENCE_SPACE_KEY = 'creds'

    env.TMS_URL = 'http://testit.ru'
    env.TMS_AUTH_TOKEN = 'tms_creds_token'
    env.TMS_ATTRIBUTE_CI_ID = 'bc435719-57be-4115-8919-0bf6c1f1ffb3'
    env.TMS_ATTRIBUTE_CONFLUENCE_REPORT = '59b135be-82a1-43d6-83eb-6c4ad50835b6'
    env.REGEXP_PATTERN_CI = /(?i)^ci[0-9]+/

    if (env.JENKINS_URL =~ JENKINS_QA) {
        env.DOCKER_CREDS = '0fd7f3e0-957e-4e3a-8e3b-b383d7af9d8a'
        env.BITBUCKET_CREDS = '0fd7f3e0-957e-4e3a-8e3b-b383d7af9d8a'
        env.VAULT_PASS_CREDS = 'os_ansible_vault'
    }
    else {
        env.DOCKER_CREDS = '0fd7f3e0-957e-4e3a-8e3b-aaaaaaaaa'
        env.BITBUCKET_CREDS = '0fd7f3e0-957e-4e3a-8e3b-bbbbbbbb'
        env.VAULT_PASS_CREDS = 'creds-vault'
    }

    log.info(sysUtils.getDate('yyyy-MM-dd_HH:mm:ss') + ' Global env load')
}
