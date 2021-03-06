pipelineJob('example') {
    displayName('Job DSL Example Project')
    description('My first job')
    deliveryPipelineConfiguration('qa', 'integration-tests')
    logRotator {
        numToKeep(10)
        artifactNumToKeep(1)
    }
    throttleConcurrentBuilds {
        maxTotal(1)
    }
}
