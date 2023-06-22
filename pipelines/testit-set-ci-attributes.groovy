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
CI_VAL = ''

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
        stage('Checkout') {
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    git.repoCheckout(
                        gitUrl: 'placeholder_git_repo/project-settings.git',
                        branch: 'master',
                    )
                }
            }
        }

        stage('Filling CI Attributes') {
            steps {
                script {
                    List projectDirs = sh(script: 'ls -d */', returnStdout: true).trim().replaceAll('/', '').split()
                    projectDirs.each { projectDir ->
                        if (projectDir =~ env.REGEXP_PATTERN_CI) {
                            Map config = readYaml(file: "${projectDir}/config.yml")
                            if (config?.tms_project_name) {
                                String projectName = config.tms_project_name
                                CI_VAL = (projectDir =~ env.REGEXP_PATTERN_CI)[0].toUpperCase()
                                log.info "projectName: ${projectName}, CI: ${CI_VAL}"

                                Object projects = tms.searchProjectByName(projectName)
                                if (projects) {
                                    Object project = projects[0]
                                    Boolean setProject = true
                                    project.testPlansAttributesScheme.each { attr ->
                                        if (attr?.id ==  env.TMS_CI_ID) {
                                            setProject = false
                                        }
                                    }
                                    if (setProject) {
                                        tms.addTestPlansAttributesByProjectId(project.id, [env.TMS_CI_ID])
                                    }

                                    Object testPlan = tms.getTestPlansByProjectId(project.id)[0]
                                    testPlan.attributes[env.TMS_CI_ID] = CI_VAL
                                    tms.updateTestPlan(testPlan)
                                }
                                else {
                                    log.error "Project: ${projectName} not found in Test IT"
                                }
                            }
                            else {
                                log.warning "Project ${projectDir} skiped, tms_project_name not defined"
                            }
                        }
                        else {
                            log.warning "Dir ${projectDir} !=~ ci[0-9]+.*"
                        }
                    }
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
