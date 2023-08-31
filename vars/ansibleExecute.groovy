#!groovy

String call(Map aMap) {
    String workDir = aMap.workDir ?: '.'
    String command = aMap.command
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

    dir(workDir) {
        ansiColor('xterm') {
            if (returnStdout) {
                stdout = shStdout(command)
            }
            else {
                sh command
            }
        }
    }

    return stdout
}
