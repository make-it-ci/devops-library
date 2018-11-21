import si.ci.jenkins.Constants
import si.ci.jenkins.Utilities

/**
 * Library implementing microservice pipelines
 *
 * @author: Zvone Gazvoda
 */
def call() {

    def utils = new Utilities(this)

    def buildDate = new Date()

    String dockerImageName = ''
    String dockerServiceName = ''
    String applicationName = ''
    String applicationVersion = ''

    String dockerPushRegistry = ''
    String dockerPullRegistry = ''

    String mavenVersion = ''

    String gitCredentialsId = Constants.GITHUB_DEVOPS_USER_ID
    String commiter = ''

    env.CICDGOAL = ''

    node {
        env.CICDGOAL = utils.getCiCdGoal(env.BRANCH_NAME, 'mirror', utils.getGitRepository(), gitCredentialsId)
    }

    print env.CICDGOAL

    if (env.CICDGOAL == '' || env.CICDGOAL == null) {
        currentBuild.result = 'NOT_BUILT'
        return
    }

    String pipelineName = utils.getMicroservicePipelineName()

    if (pipelineName.equals('')) {
        currentBuild.result = 'NOT_BUILT'
    }

    print "Executing $pipelineName due to CI/CD goal: $env.CICDGOAL on branch: $env.BRANCH_NAME"

    switch (pipelineName) {
        case "docker-microservice-pipeline":
            pipeline {
                agent {
                    label 'swarm'
                }

                stages {
                    stage('Develop: Initialize') {
                        steps {
                            script {
                                lastCommit = utils.getLastCommit()

                                applicationName = utils.getOrganizationName()

                                dockerImageName = "/" + applicationName + "/"

                                dockerPushRegistry = env.DOCKER_PUSH_REGISTRY
                                dockerPullRegistry = env.DOCKER_PULL_REGISTRY

                                pom = readMavenPom file: 'pom.xml'

                                dockerServiceName = pom.artifactId

                                dockerImageName = dockerImageName + pom.artifactId + ":"

                                mavenVersion = pom.version

                                commiter = utils.getCommiter()
                                echo "Commiter: " + commiter

                                if (env.BRANCH_NAME == "develop" || env.BRANCH_NAME.startsWith("hotfix")) {
                                    applicationVersion = utils.getDockerTag(pom.version)
                                    if (env.BRANCH_NAME.startsWith('hotfix')) {
                                        applicationVersion = applicationVersion + '-SNAPSHOT'
                                    }
                                } else {
                                    applicationVersion = utils.getDockerTagWithBranch(pom.version)
                                }

                            }
                        }
                    }

                    stage('Develop: Build Java') {
                        /*agent {
                            docker {
                                image 'nexus-ci.kumuluz.com/maven-git-alpine:1.0.0'
                                reuseNode true
                            }
                        }*/
                        steps {
                            configFileProvider([configFile(fileId: 'maven-nexus-settings', variable: 'MAVEN_SETTINGS')]) {
                                // build
                                script {
                                    if (env.BRANCH_NAME.startsWith("hotfix")) {
                                        sh "mvn versions:set -DnewVersion=$mavenVersion-SNAPSHOT"
                                        sh "mvn versions:update-child-modules"
                                    }

                                    sh 'mvn -U -s $MAVEN_SETTINGS clean package'

                                    try {
                                        junit '**/target/surefire-reports/*.xml'
                                    } catch (err) {
                                        echo "No test report files were found. Skipping publishing of test reports."
                                    }

                                    if (env.BRANCH_NAME == "develop" || env.BRANCH_NAME.startsWith("hotfix")) {
                                        //deploy maven artifacts
                                        sh "mvn -U -s $MAVEN_SETTINGS -Dbuild.date=\"$buildDate\" -Dgit.commit=\"$lastCommit\"  deploy"
                                    }

                                    stash includes: '**/target/**/*', name: 'target'
                                }
                            }
                        }
                    }

                    stage("Develop: Build Docker image") {
                        steps {

                            unstash 'target'

                            script {

                                //odstraniti ta blok, če je ok, da se premakne v init stage!!!
                                if (env.BRANCH_NAME == "develop" || env.BRANCH_NAME.startsWith("hotfix")) {
                                    applicationVersion = utils.getDockerTag(pom.version)
                                    if (env.BRANCH_NAME.startsWith('hotfix')) {
                                        applicationVersion = applicationVersion + '-SNAPSHOT'
                                    }
                                } else {
                                    applicationVersion = utils.getDockerTagWithBranch(pom.version)
                                }
                                /*** **/

                                dockerImageName = dockerImageName + applicationVersion
                                def dockerImage = docker.build(dockerPushRegistry + dockerImageName)

                                docker.withRegistry('https://nexus-ci.kumuluz.com', 'nexus-ci-zvoneg') {
                                    dockerImage.push()
                                    if (env.BRANCH_NAME == "develop") {
                                        dockerImage.push('latest')
                                    }
                                }
                            }
                        }
                    }

                    stage('Develop: Deploy to Docker Swarm') {

                        steps {
                            // update docker-compose.yml
                            script {
                                def dockerComposeYml = 'docker-compose.yml'

                                dir('.ci') {
                                    if (!fileExists(dockerComposeYml)) {
                                        currentBuild.result = 'ABORTED'
                                        error('Stopping early, .ci/docker-compose.yml not found.')
                                    }

                                    dockerCompose = readFile file: dockerComposeYml, encoding: 'UTF-8'

                                    if (!dockerCompose.contains("\${IMAGE_NAME}")) {
                                        currentBuild.result = 'ABORTED'
                                        error('Stopping early, cannot find "\${IMAGE_NAME} in .ci/docker-compose.yml.')
                                    }

                                    dockerCompose = dockerCompose.replace("\${IMAGE_NAME}", dockerPullRegistry + dockerImageName)
                                    writeFile file: "docker-compose.yml", text: dockerCompose, encoding: 'UTF-8'

                                    //Deploy to Docker Swarm:
                                    sh "docker stack deploy -c docker-compose.yml ${dockerServiceName}"
                                }
                            }
                        }
                    }
                }
                post {
                    always {
                        deleteDir()
                    }
                    success {
                        script {
                            echo "The pipeline ${currentBuild.fullDisplayName} completed successfully."
                            sendNotifications 'SUCCESS', "${env.CICDGOAL} from ${commiter}"
                        }
                    }
                    unstable {
                        script {
                            echo "The pipeline ${currentBuild.fullDisplayName} is unstable."
                            sendNotifications 'UNSTABLE', env.CICDGOAL
                        }
                    }
                    failure {
                        script {
                            echo "The pipeline ${currentBuild.fullDisplayName} failed."
                            sendNotifications 'FAILED', "${env.CICDGOAL} from ${commiter}"
                        }
                    }
                }
            }
            break

        case "docker-microservice-prepare-release-pipeline":
            pipeline {
                agent { label 'swarm' }

                stages {
                    stage('Release-Prepare: Validate consistency') {
                        steps {
                            script {
                                withCredentials([usernamePassword(credentialsId: gitCredentialsId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')])
                                        {
                                            sh "git config http.sslVerify false"

                                            GIT_PASSWORD = URLEncoder.encode(GIT_PASSWORD, "UTF-8")

                                            sh "git remote set-url origin https://${GIT_USERNAME}:${GIT_PASSWORD}@${utils.getGitRepository()}"
                                            sh "git config user.email 'zvone.gazvoda@cloud.si'"
                                            sh "git config user.name ${GIT_USERNAME}"

                                            utils.checkConsistency('Release-Prepare: Validate consistency', "0")
                                        }
                            }
                        }
                    }

                    stage('Release-Prepare: Create release branch') {
                        steps {
                            configFileProvider([configFile(fileId: 'maven-nexus-settings', variable: 'MAVEN_SETTINGS')]) {
                                // build
                                script {
                                    if (gitCredentialsId != null) {
                                        withCredentials([usernamePassword(credentialsId: gitCredentialsId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {

                                            sh "git config http.sslVerify false"

                                            GIT_PASSWORD = URLEncoder.encode(GIT_PASSWORD, "UTF-8")

                                            print "https://${GIT_USERNAME}:${GIT_PASSWORD}@${utils.getGitRepository()}"

                                            sh "git config user.email 'zvone.gazvoda@cloud.si'"
                                            sh "git config user.name ${GIT_USERNAME}"

                                            sh "git remote set-url origin https://${GIT_USERNAME}:${GIT_PASSWORD}@${utils.getGitRepository()}"

                                            sh "mvn -B -s $MAVEN_SETTINGS  gitflow:release-start -DargLine=\"-s $MAVEN_SETTINGS\" -DpushRemote=true -DversionDigitToIncrement=1 -DinstallProject=true -DcommitDevelopmentVersionAtStart=true -Dverbose=true"
                                        }
                                    } else {
                                        currentBuild.result = 'FAILURE'
                                        error('Git credentials are invalid. Unable to execute stage Release-Prepare: Create release branch.')
                                    }
                                }
                            }
                        }
                    }
                }
                post {
                    always {
                        deleteDir()
                    }
                    success {
                        echo "The pipeline ${currentBuild.fullDisplayName} completed successfully."
                        sendNotifications 'SUCCESS', env.CICDGOAL
                    }
                    unstable {
                        echo "The pipeline ${currentBuild.fullDisplayName} is unstable."
                        sendNotifications 'UNSTABLE', env.CICDGOAL
                    }
                    failure {
                        echo "The pipeline ${currentBuild.fullDisplayName} failed."
                        sendNotifications 'FAILED', env.CICDGOAL
                    }
                }
            }
            break

        case "docker-microservice-deploy-pipeline":
            pipeline {
                agent { label 'swarm' }

                stages {
                    stage('Release/Hotfix-Deploy: Initialize') {
                        steps {
                            script {
                                lastCommit = utils.getLastCommit()

                                applicationName = utils.getOrganizationName()

                                dockerImageName = "/" + applicationName + "/"

                                dockerPushRegistry = env.DOCKER_PUSH_REGISTRY
                                dockerPullRegistry = env.DOCKER_PULL_REGISTRY

                                pom = readMavenPom file: 'pom.xml'

                                dockerServiceName = pom.artifactId
                                echo "Docker service name; " + dockerServiceName

                                dockerImageName = dockerImageName + pom.artifactId + ":"

                                mavenVersion = pom.version

                                commiter = utils.getCommiter()
                                echo "Commiter: " + commiter

                            }
                        }
                    }

                    stage('Release/Hotfix-Deploy: Validate consistency') {
                        steps {
                            script {
                                withCredentials([usernamePassword(credentialsId: gitCredentialsId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')])
                                        {
                                            sh "git config http.sslVerify false"

                                            GIT_PASSWORD = URLEncoder.encode(GIT_PASSWORD, "UTF-8")

                                            sh "git remote set-url origin https://${GIT_USERNAME}:${GIT_PASSWORD}@${utils.getGitRepository()}"
                                            sh "git config user.email 'zvone.gazvoda@cloud.si'"
                                            sh "git config user.name ${GIT_USERNAME}"

                                            utils.checkConsistency('Release/Hotfix-Deploy: Validate consistency', "1")
                                        }
                            }
                        }
                    }

                    stage('Release/Hotfix-Deploy: Build Java') {
                        steps {
                            configFileProvider([configFile(fileId: 'maven-nexus-settings', variable: 'MAVEN_SETTINGS')]) {
                                // build
                                script {
                                    // check if snapshots
                                    snapshots = sh(script: "mvn -s ${MAVEN_SETTINGS}  dependency:tree | grep '\\-SNAPSHOT' | wc -l", returnStdout: true)

                                    if (snapshots.trim() != "0") {
                                        currentBuild.result = 'FAILURE'
                                        error('Aborting Release/Hotfix Deploy! The project modules or dependencies contain SNAPSHOT versions.')
                                    }

                                    sh "mvn -U -s $MAVEN_SETTINGS  clean package"

                                    try {
                                        junit '**/target/surefire-reports/*.xml'
                                    } catch (err) {
                                        echo "No test report files were found. Skipping publishing of test reports."
                                    }

                                    sh "mvn -U -s $MAVEN_SETTINGS -Dbuild.date=\"$buildDate\" -Dgit.commit=\"$lastCommit\"  deploy"

                                    stash includes: '**/target/**/*', name: 'target'
                                }
                            }
                        }
                    }

                    stage("Release/Hotfix-Deploy: Build Docker image") {
                        steps {

                            unstash 'target'

                            script {
                                applicationVersion = utils.getDockerTag(pom.version)

                                dockerImageName = dockerImageName + applicationVersion
                                def dockerImage = docker.build(dockerPushRegistry + dockerImageName)

                                docker.withRegistry('https://nexus-ci.kumuluz.com', 'nexus-ci-zvoneg') {
                                    dockerImage.push()
                                    if (env.BRANCH_NAME.startsWith('release'))
                                        dockerImage.push('latest')
                                }
                            }
                        }
                    }

                    stage('Release/Hotfix-Deploy: Deploy to Docker Swarm') {
                        steps {
                            // update docker-compose.yml
                            script {
                                def dockerComposeYml = 'docker-compose.yml'

                                //dir('digital-devops/' + applicationName + "_" + dockerServiceName) {
                                //    git branch: 'master', url: utils.getGitHost() + '/digital-devops/' + applicationName + "_" + dockerServiceName + '.git', credentialsId: gitCredentialsId

                                    dir('.ci') {
                                        if (!fileExists(dockerComposeYml)) {
                                            currentBuild.result = 'ABORTED'
                                            error('Stopping early, .ci/docker-compose.yml not found.')
                                        }

                                        dockerCompose = readFile file: dockerComposeYml, encoding: 'UTF-8'

                                        if (!dockerCompose.contains("\${IMAGE_NAME}")) {
                                            currentBuild.result = 'ABORTED'
                                            error('Stopping early, cannot find "\${IMAGE_NAME} in .ci/docker-compose.yml.')
                                        }

                                        dockerCompose = dockerCompose.replace("\${IMAGE_NAME}", dockerPullRegistry + dockerImageName)
                                        writeFile file: "docker-compose.yml", text: dockerCompose, encoding: 'UTF-8'

                                        sh "docker stack deploy -c docker-compose.yml ${dockerServiceName}"
                                    }
                                //}
                            }
                        }
                    }

                    stage("Release/Hotfix-Deploy: Bump version on release/hotfix branch") {
                        steps {
                            configFileProvider([configFile(fileId: 'maven-nexus-settings', variable: 'MAVEN_SETTINGS')]) {

                                script {

                                    mavenVersion = utils.increasePatchVersion(mavenVersion)
                                    sh "mvn  versions:set -DnewVersion=$mavenVersion"
                                    sh "mvn  versions:update-child-modules"

                                    sh 'git add .'
                                    sh 'git reset .ci/docker-compose.yml'
                                    sh "git commit -m 'Bump version for next fix'"

                                    sh(script: "git push origin ${env.BRANCH_NAME}", returnStatus: true)
                                }
                            }
                        }
                    }
                }
                post {
                    always {
                        deleteDir()
                    }
                    success {
                        echo "The pipeline ${currentBuild.fullDisplayName} completed successfully."
                        sendNotifications 'SUCCESS', env.CICDGOAL
                    }
                    unstable {
                        echo "The pipeline ${currentBuild.fullDisplayName} is unstable."
                        sendNotifications 'UNSTABLE', env.CICDGOAL
                    }
                    failure {
                        echo "The pipeline ${currentBuild.fullDisplayName} failed."
                        sendNotifications 'FAILED', env.CICDGOAL
                    }
                }
            }
            break

        case 'docker-microservice-finish-pipeline':
            pipeline {
                agent { label 'swarm' }

                stages {
                    stage('Finish: Initialize') {
                        steps {
                            script {
                                // last commit triggered the following build - dummy commit to trigger finish
                                thirdLastCommit = utils.getThirdLastCommit()

                                pom = readMavenPom file: 'pom.xml'

                                mavenVersion = pom.version

                            }
                        }
                    }

                    stage('Finish: Tag and merge') {

                        steps {
                            configFileProvider([configFile(fileId: 'maven-nexus-settings', variable: 'MAVEN_SETTINGS')]) {
                                // build
                                script {
                                    if (gitCredentialsId != null) {
                                        withCredentials([usernamePassword(credentialsId: gitCredentialsId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')])
                                                {
                                                    sh "git config http.sslVerify false"

                                                    GIT_PASSWORD = URLEncoder.encode(GIT_PASSWORD, "UTF-8")

                                                    sh "git remote set-url origin https://${GIT_USERNAME}:${GIT_PASSWORD}@${utils.getGitRepository()}"

                                                    sh "git config user.email 'zvone.gazvoda@cloud.si'"
                                                    sh "git config user.name ${GIT_USERNAME}"

                                                    utils.checkConsistency('Finish: Tag and merge', "1")

                                                    //isReleaseChanged = 0

                                                    //deployCommit = utils.parseCommitMsg(env.CICDGOAL)

                                                    //echo "Deploy commitId: $deployCommit"
                                                    echo "Third last commitId: $thirdLastCommit"

                                                    //isReleaseChanged = (deployCommit != thirdLastCommit)

                                                    isDeployCommit = utils.isDeployCommit(thirdLastCommit)

                                                    //if (isReleaseChanged || !isDeployCommit) {
                                                    if (!isDeployCommit) {
                                                        currentBuild.result = 'FAILURE'
                                                        error('Aborting release! The release branch was changed after the release artifact was built or [ci-deploy] was not executed before [ci-finish].')
                                                    }

                                                    utils.finalizeRelease(env.BRANCH_NAME, mavenVersion, thirdLastCommit)

                                                }
                                    } else {
                                        currentBuild.result = 'FAILURE'
                                        error('Git credentials are invalid. Unable to remove remote branch.')
                                    }
                                }
                            }
                        }
                    }
                }

                post {
                    always {
                        deleteDir()
                    }
                    success {
                        echo "The pipeline ${currentBuild.fullDisplayName} completed successfully."
                        sendNotifications 'SUCCESS', env.CICDGOAL
                    }
                    unstable {
                        echo "The pipeline ${currentBuild.fullDisplayName} is unstable."
                        sendNotifications 'UNSTABLE', env.CICDGOAL
                    }
                    failure {
                        echo "The pipeline ${currentBuild.fullDisplayName} failed."
                        sendNotifications 'FAILED', env.CICDGOAL
                    }
                }
            }
            break

        case 'docker-microservice-cancel-pipeline':
            pipeline {
                agent { label 'swarm' }

                stages {
                    stage('Release/Hotfix-Cancel: Remove branch') {
                        steps {
                            script {
                                if (gitCredentialsId != null) {
                                    withCredentials([usernamePassword(credentialsId: gitCredentialsId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')])
                                            {
                                                GIT_PASSWORD = URLEncoder.encode(GIT_PASSWORD, "UTF-8")

                                                sh "git config http.sslVerify false"

                                                sh "git remote set-url origin https://${GIT_USERNAME}:${GIT_PASSWORD}@${utils.getGitRepository()}"

                                                print "Removing branch: $env.BRANCH_NAME"

                                                isRemoved = sh(script: "git push origin --delete ${env.BRANCH_NAME}", returnStatus: true)

                                                if (isRemoved != 0) {
                                                    currentBuild.result = 'FAILURE'
                                                    error("Aborting release/hotfix cancel! Unable to remove branch $env.BRANCH_NAME from remote.")
                                                }
                                            }
                                } else {
                                    currentBuild.result = 'FAILURE'
                                    error('Git credentials are invalid. Unable to remove remote branch.')
                                }
                            }
                        }
                    }
                }
                post {
                    always {
                        deleteDir()
                    }
                    success {
                        echo "The pipeline ${currentBuild.fullDisplayName} completed successfully."
                        sendNotifications 'SUCCESS', env.CICDGOAL
                    }
                    unstable {
                        echo "The pipeline ${currentBuild.fullDisplayName} is unstable."
                        sendNotifications 'UNSTABLE', env.CICDGOAL
                    }
                    failure {
                        echo "The pipeline ${currentBuild.fullDisplayName} failed."
                        sendNotifications 'FAILED', env.CICDGOAL
                    }
                }
            }
            break

        case 'docker-microservice-prepare-hotfix-pipeline':
            pipeline {
                agent { label 'swarm' }

                stages {
                    stage('Hotfix-Prepare: Check existing branches') {
                        steps {
                            script {
                                existingReleases = sh(script: "git branch -r | grep 'release\\|hotfix' | wc -l", returnStdout: true)

                                print "Existing releases: $existingReleases"

                                if (existingReleases.trim() != "0") {
                                    currentBuild.result = 'FAILURE'
                                    error("Aborting - Hotfix-Prepare: There are existing release or hotfix branches on remote repository. Finalize existing release/hotfix in order to create a new one.")
                                }
                            }
                        }
                    }
                    stage('Hotfix-Prepare: Create hotfix branch') {
                        steps {
                            configFileProvider([configFile(fileId: 'maven-nexus-settings', variable: 'MAVEN_SETTINGS')]) {
                                // build
                                script {
                                    if (gitCredentialsId != null) {
                                        withCredentials([usernamePassword(credentialsId: gitCredentialsId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {

                                            sh "git config http.sslVerify false"

                                            GIT_PASSWORD = URLEncoder.encode(GIT_PASSWORD, "UTF-8")

                                            print "https://${GIT_USERNAME}:${GIT_PASSWORD}@${utils.getGitRepository()}"

                                            sh "git config user.email 'zvone.gazvoda@cloud.si'"
                                            sh "git config user.name ${GIT_USERNAME}"

                                            sh "git remote set-url origin https://${GIT_USERNAME}:${GIT_PASSWORD}@${utils.getGitRepository()}"

                                            sh 'mvn -B -s $MAVEN_SETTINGS  gitflow:hotfix-start -DargLine=\"-s $MAVEN_SETTINGS\" -DpushRemote=true -DinstallProject=true -Dverbose=true'
                                        }
                                    } else {
                                        currentBuild.result = 'FAILURE'
                                        error('Git credentials are invalid. Unable to execute stage Hotfix-Prepare: Create hotfix branch.')
                                    }
                                }
                            }
                        }
                    }
                }
                post {
                    always {
                        deleteDir()
                    }
                    success {
                        echo "The pipeline ${currentBuild.fullDisplayName} completed successfully."
                        sendNotifications 'SUCCESS', env.CICDGOAL
                    }
                    unstable {
                        echo "The pipeline ${currentBuild.fullDisplayName} is unstable."
                        sendNotifications 'UNSTABLE', env.CICDGOAL
                    }
                    failure {
                        echo "The pipeline ${currentBuild.fullDisplayName} failed."
                        sendNotifications 'FAILED', env.CICDGOAL
                    }
                }
            }
            break
    }
}
