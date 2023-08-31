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
    ])
])

pipeline {
    agent {
        label 'masterLin'
    }

    triggers {
        cron('00 * * * *')
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
            steps {
                script {
                    Object projects = tms.searchProjectByName(params.TMS_PROJECT_NAME)
                    projects.each { project ->
                        Object testRuns = tms.getTestRunsByProjectId(project.id)
                        testRuns.each { testRun ->
                            log.warning project.name
                            String startedOnDate = (testRun.startedOn =~ /[0-9]+-[0-9]+-[0-9]+/)[0]
                            if (sysUtils.compareCurrentDate(startedOnDate, 1)) {
                                log.info 'Тест ран запущен меньше 1 дня'
                                def completedOnTimestamp = testRun.testResults
                                    .collect { result ->
                                        result.completedOn.toString().equalsIgnoreCase('null') ? [] : sysUtils.convertDateToTimestamp(result.completedOn)
                                    }
                                    .findAll()
                                if (completedOnTimestamp) {
                                    long diffTimestamp = (System.currentTimeMillis() - completedOnTimestamp.max()) / (1000 * 60 * 60)
                                    if (diffTimestamp > 3) {
                                        log.info 'Последний запущенный тест обновился больше 3х часов назад'
                                        skipTests(testRun)
                                    }
                                }
                                else {
                                    long testRunTime = (System.currentTimeMillis() - sysUtils.convertDateToTimestamp(testRun.startedOn)) / (1000 * 60 * 60)
                                    if (testRunTime > 3) {
                                        log.info 'Тест ран запущен больше 3х часов, результатов тестов всё еще нет. Останавливаем.'
                                        skipTests(testRun)
                                    }
                                }
                            }
                            else {
                                log.info 'Тест ран запущен больше 1 дня. Останавливаем'
                                skipTests(testRun)
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

void skipTests(Object testRun) {
    List testResults = []
    testRun.testResults.findAll { test ->
        test.outcome.equalsIgnoreCase('InProgress')
    }.findResults { test ->
        testResults += [
            configurationId: test.configuration.id,
            autoTestExternalId: test.autoTest.externalId,
            outcome: 'Skipped',
            message: 'Skipped because jenkins job no running or failed',
        ]
    }
    tms.setResultToTestRuns(testRun.id, testResults)
    String [] tests = testRun.testResults.collect { test ->
        test.outcome.equalsIgnoreCase('InProgress') ? test.autoTest.name : []
    }
    .findAll()
    log.info 'Skipped tests: \n' + tests.join('\n')
}
