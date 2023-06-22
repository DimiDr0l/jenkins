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
