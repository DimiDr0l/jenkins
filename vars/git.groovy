#!groovy

void repoCheckout(Map aMap) {
    String gitUrl = aMap.gitUrl
    String gitCreds = aMap.gitCreds ?: env.BITBUCKET_CREDS
    String branch = aMap.branch ?: 'master'
    String targetDir = aMap.targetDir ?: '.'

    checkout(
        changelog: false,
        poll: false,
        scm: [
            $class: 'GitSCM',
            branches: [[name: branch]],
            extensions: [
                [$class: 'CheckoutOption', timeout: 20],
                [
                    $class: 'RelativeTargetDirectory',
                    relativeTargetDir: targetDir
                ],
                [
                    $class : 'CloneOption',
                    depth  : 0,
                    noTags : false,
                    shallow: true,
                    timeout: 120
                ]
            ],
            userRemoteConfigs: [[
                credentialsId: gitCreds,
                url: gitUrl
            ]]
        ]
    )
}

void push(String branch, String gitCreds = env.BITBUCKET_CREDS) {
    String filePath = env.WORKSPACE + '/git_helper.sh'
    writeFile(
        file: filePath,
        text: '''\
            #!/usr/bin/bash
            echo username="$GIT_USERNAME"
            echo password="$GIT_PASSWORD"
            '''.stripIndent()
    )
    sh 'git config push.default simple'
    sh 'git config credential.helper "/bin/bash ' + filePath + '"'
    withCredentials([usernamePassword(credentialsId: gitCreds, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
        sh 'git push origin HEAD:' + branch
    }
}
