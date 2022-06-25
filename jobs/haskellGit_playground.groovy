import common.Constants



def call(slackChannel='', dockerImage='', project_name='', final_image_w_tag='', ecs_cluster='', ecs_service='', forceFast=true, updateGHCnStack=false){
    if(!dockerImage){
        dockerImage = Constants.HASKELL_DOCKER_IMAGE
    }

    def entity_exists = ''
    def gitCommit = ''
    def CUSTOMER = JOB_NAME.split('/')[1]
    def ENVIRONMENT = BRANCH_NAME

    try{
        stage('policies'){
            // Setup basin config. Setting number and lifetime of artifacts to be stored and disallow concurrent builds
            // TODO: currently i haven't found way to setup globaly, or even to call it once. Find the solution
            common.baseConfig()
        }
        stage('checkout & prepare') {
            checkOutResults = checkout scm
            gitCommit = checkOutResults['GIT_COMMIT']
            author = buildAuthor.getAuthorName()
            sh "echo ${author}"
            sh "echo Project: ${project_name}"
            sh "echo Image: ${final_image_w_tag}"
            sh "echo ECS cluster: ${ecs_cluster}"
            sh "echo ECS service: ${ecs_service}"
            sh 'env'
        }
        stage('initialize python helper') {
            initializing_python()
        }
        // stage('build consul entity') {
        //     entity_exists = sh(returnStdout: true, script: 'python3 $WORKSPACE/python-utils/sample_consul.py').trim()
        //     println entity_exists
        //     run_consul_builder()
        // }
        slackStatus common.getSlackChannel(slackChannel), {
            sh "echo  GIT:::::---:${gitCommit}"
            sh "echo ${checkOutResults}"
            statusNotifier{
                stage('restore & build & archive'){
                    def stackRootMd5;
                    def stackWorkMd5;
                    docker.image(dockerImage).inside('-u 0:0') {
                        stage('Update Docker container'){
                            sh 'apt-get update -qq && apt-get install -y postgresql-client libpq-dev mercurial git md5deep libbz2-dev'
                        }
                        stage('restore artifacts root & work'){
                            restoreArtifacts()
                            stackRootMd5 = common.getMd5Deep('/root/.stack')
                            // Experimental feature
                            stackWorkMd5 = common.getMd5Deep('.stack-work/downloaded  .stack-work/install')
                            print "Current md5: ${stackRootMd5} ${stackWorkMd5}"
                        }
                        stage('build'){
                            withCredentials([file(credentialsId: 'jenkins_private_key', variable: 'PK_SECRET')]) {
                                sh "mkdir ~/.ssh"
                                sh 'echo "Host bitbucket.org\n HostName bitbucket.org\n IdentityFile ${PK_SECRET}\n StrictHostKeyChecking no\n " > ~/.ssh/config'
                                sh 'echo "Host github.com\n HostName github.com\n IdentityFile ${PK_SECRET}\n StrictHostKeyChecking no\n " >> ~/.ssh/config'
                                build(forceFast, updateGHCnStack)
                                sh "echo ${checkOutResults}"
                                sh "echo  GIT:::::---:${gitCommit}"
                            }
                        }
                        stage('archive artifacts root & work'){
                            storeArtifacts(stackRootMd5,stackWorkMd5)
                        }
                    }
                }
                // def project_name = sh(returnStdout: true, script: "consul kv get ${CUSTOMER}/${ENVIRONMENT}/jenkins/project").trim()
                // def project_name = "forescout-suiteshare"

                // gitCommit = checkOutResults['GIT_COMMIT']

                stage('create bundle') {
                    sh 'ls -l'
                    sh "mkdir -p ./dist/bin && find . -type f -name ${project_name} -exec cp {} ./dist/bin \\;"
                    sh 'ls -l ./dist/bin'
                    sh "tar czf ${project_name}.keter-${gitCommit}-$BUILD_ID ./dist/bin/${project_name} config static"
                //     run_artifactory_builder(gitCommit, project_name)
                }
                stage('Build & push Docker image') {
                    docker.withRegistry('https://546878105594.dkr.ecr.us-east-1.amazonaws.com', 'ecr:us-east-1:zoominsoftware-dev') {
                        sh "sed -i 's/yesod-simple/${project_name}/g' Dockerfile && sed -i 's/env/development/g' Dockerfile"
                        sh "cp ./dist/bin/${project_name} ."
                        sh "cat Dockerfile"
                        def customImage = docker.build(final_image_w_tag)

                        /* Push the container to the custom Registry */
                        customImage.push()
                        update_ecs_service(ecs_cluster, ecs_service)
                        // sleep(time:30, unit:"SECONDS")
                    }
                }
            }
        }
    }
    catch (Exception ex){
        if (entity_exists == 'false'){
            consul_entity_cleaner()
        }
        throw new Exception(ex)
    }
    finally {
        stage('Cleanup workspace'){
            sh 'sudo rm -rf .stack-work'
            deleteDir();
        }

    }
}


def build(forceFast=true, updateGHCnStack){
    def fastBuild = forceFast?'--fast':''
    // if(!common.isDeployable()){
    //     fastBuild = forceFast?'--fast':''
    // }
    if(updateGHCnStack){
        sh 'stack clean --install-ghc --allow-different-user'
        sh 'stack update'
    }else{
        sh 'stack clean --allow-different-user'
    }
    sh "export LC_LANG=C.UTF-8; export LC_ALL=C.UTF-8; stack build ${fastBuild} --system-ghc --allow-different-user"
    sh 'ls -la'
}

// TODO: think if we need such separation
def storeArtifacts(stackRootMd5, stackWorkMd5){
    def stackRootMd5New = common.getMd5Deep('/root/.stack')
    // Experimental feature
    def stackWorkMd5New = common.getMd5Deep('.stack-work/downloaded  .stack-work/install')
    print "New md5: ${stackRootMd5New} | ${stackWorkMd5New}"
    if(stackRootMd5New != stackRootMd5){
        print "Stack root has changed: ${stackRootMd5New}. Uploading new."
        artifacts.storeArtifacts('stack-root','-C /root .stack')
    } else {
        print "Stack root is the same: ${stackRootMd5New}. Uploading previous."
        artifacts.storeArtifacts('stack-root','',true)
    }

    // In case of using  'stack clean' stack-work will always be changed
    // artifacts.storeArtifacts('stack-work','.stack-work')
    //*****
    // Experimental feature
    if(stackWorkMd5New != stackWorkMd5){
        print "Stack work has changed: ${stackWorkMd5New}. Uploading new."
        artifacts.storeArtifacts('stack-work','.stack-work')
    } else {
        print "Stack work is the same: ${stackWorkMd5New}. Uploading previous."
        artifacts.storeArtifacts('stack-work','',true)
    }
}

def restoreArtifacts(){
    artifacts.restoreArtifacts('stack-root','master','-C /root .stack', false, false)
    // Do not allow to take `.stack-work` from another branch, it will fail anyway.
    // TODO: undersand why it fails
    // artifacts.restoreArtifacts('stack-work','','', false, false)
}

def initializing_python(){
    sh 'rm -rf python-utils'
    sh 'mkdir python-utils/'
    dir('python-utils'){
        git branch: 'master', credentialsId: Constants.JENKINS_GITHUB_CREDENTIALS, url: Constants.PYTHON_BUILDER_LIBRARY_GITHUB
        sh "sudo apt-get update"
        sh "sudo apt-get -y install python3-pip"
        sh 'sudo pip3 install --user -r requirements.txt'
    }
}

def run_consul_builder(){
    sh 'python3 $WORKSPACE/python-utils/consul_stage.py'
}

def run_artifactory_builder(gitCommit, project_name){
    sh "python3 $WORKSPACE/python-utils/artifactory_stage.py ${project_name}.keter-${gitCommit}-$BUILD_ID"
}

def consul_entity_cleaner(){
    sh 'python3 $WORKSPACE/python-utils/clean_consul_entity.py'
}

def update_ecs_service(cluster, service){
    withCredentials([[
        $class: 'AmazonWebServicesCredentialsBinding',
        credentialsId: "zoominsoftware-dev	",
        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        ]]) {
            sh "aws ecs update-service --cluster ${cluster} --service ${service} --force-new-deployment"
        }
}
