String call(Map aMap) {
    String workDir = aMap.workDir ?: '.'
    String command = aMap.command
    String registryCredentialId = aMap.registryCredentialId ?: env.DOCKER_CREDS
    String registryURL = aMap.registryURL ?: env.DOCKER_REGISTRY ? 'https://' + env.DOCKER_REGISTRY : ''
    String dockerImage = aMap.dockerImage ?: env.CHAOS_IMAGE
    String dockerArgs = aMap.dockerArgs ?: ''
    String vaultPassCreds = aMap.vaultPassCreds ?: env.VAULT_PASS_CREDS
    String vaultPassPath = aMap.vaultPassPath ?: env.VAULT_PASSWORD
    String privateKeyCreds = aMap.privateKeyCreds ?: env.PRIVATE_KEY_CREDS
    String privateKeyPath = aMap.privateKeyPath ?: env.PRIVATE_KEY
    Boolean returnStdout = aMap.returnStdout ?: false
    String stdout = ''

    if (!command) {
        log.fatal 'argument command is required'
    }

    if (vaultPassCreds && vaultPassPath) {
        withCredentials([file(credentialsId: vaultPassCreds, variable: 'VAULT')]) {
            sh "cp -f ${VAULT} ${vaultPassPath}"
        }
    }

    if (privateKeyCreds && privateKeyPath) {
        withCredentials([file(credentialsId: privateKeyCreds, variable: 'KEY')]) {
            sh "cp -f ${KEY} ${privateKeyPath}"
        }
    }

    //Hack docker passwd for ansible (KeyError: 'getpwuid(): uid not found: 1000')
    String passwdTemplate = """\
        root:x:0:0:root:/root:/bin/bash
        jenkins:x:1000:1000:jenkins:${env.WORKSPACE}:/bin/bash
    """.stripIndent()
    writeFile(
        file: env.WORKSPACE + '/docker_passwd',
        text: passwdTemplate
    )

    docker.withRegistry(registryURL, registryCredentialId) {
        docker.image(dockerImage).inside(\
                                        ' -v $WORKSPACE/docker_passwd:/etc/passwd:ro ' +
                                        dockerArgs +
                                        ' --entrypoint=""') {
            dir(workDir) {
                if (returnStdout) {
                    stdout = sh(returnStdout: true, script: command).trim()
                }
                else {
                    sh command
                }
            }
        }
    }
    return stdout
}
