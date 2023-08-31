#!groovy

Map call(Map aMap = [:]) {
    String name = aMap.name ?: 'ansible'
    String label = aMap.label ?: ''
    String cloud = aMap.cloud ?: env.KUBERNETES_CLOUD
    String namespace = aMap.namespace ?: ''
    String nodeSelector = aMap.nodeSelector ?: ''
    Integer retries = aMap.retries ?: 2
    String jnlpImage = aMap.jnlpImage ?: env.KUBERNETES_JNLP_IMAGE
    String ansibleImage = aMap.ansibleImage ?: env.CHAOS_IMAGE
    String limitsMemory = aMap.limitsMemory ?: env.CONTAINER_LIMITS_MEMORY ?: '8Gi'
    String limitsCpu = aMap.limitsCpu ?: env.CONTAINER_LIMITS_CPU ?: '4'
    String [] imagePullSecrets = \
        aMap.imagePullSecrets ?: ['docker-pullsecret1', 'docker-pullsecret2']

    Map k8sConfig = [:]

    List tmplImagePullSecrets = []
    imagePullSecrets.each { secret ->
        tmplImagePullSecrets += [
            'name': secret
        ]
    }

    Map templates = [
        'spec': [
            'containers': [],
            'imagePullSecrets': tmplImagePullSecrets,
        ]
    ]
    templates.spec.containers += tmplContainer(cName: 'jnlp', image: jnlpImage)

    if (name == 'ansible') {
        templates.spec.containers += tmplContainer(
            cName: name,
            image: ansibleImage,
            imagePullPolicy: 'Always',
            tty: true,
            limitsMemory: limitsMemory,
            limitsCpu: limitsCpu
        )
    }

    if (label) {
        k8sConfig.label = label
    }
    if (nodeSelector) {
        k8sConfig.nodeSelector = nodeSelector
    }
    if (namespace) {
        k8sConfig.namespace = namespace
    }

    String yamlTemplate = writeYaml(returnText: true, data: templates)
    k8sConfig.cloud = cloud
    k8sConfig.retries = retries
    k8sConfig.yaml = yamlTemplate
    k8sConfig.defaultContainer = name

    return k8sConfig
}

List tmplContainer(Map aMap) {
    String cName = aMap.cName
    String image = aMap.image
    String imagePullPolicy = aMap.imagePullPolicy ?: 'IfNotPresent'
    String limitsMemory = aMap.limitsMemory ?: '2Gi'
    String limitsCpu = aMap.limitsCpu ?: '2'
    Boolean tty = aMap.tty ?: false

    Map tmpl = [
        'name': cName,
        'image': image,
        'imagePullPolicy': imagePullPolicy,
        'resources': [
            'limits': [
                'memory': limitsMemory,
                'cpu': limitsCpu
            ],
            'requests': [
                'memory': '1Gi',
                'cpu': '1'
            ]
        ]
    ]

    if (tty) {
        tmpl.tty = 'true'
    }

    return [tmpl]
}
