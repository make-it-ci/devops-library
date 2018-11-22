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

    String commiter = ''

    pipeline {
        agent {
            label 'swarm'
        }

        stages {
            stage('Initialize') {
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

            stage('Build Java') {
                steps {
                    configFileProvider([configFile(fileId: 'maven-nexus-settings', variable: 'MAVEN_SETTINGS')]) {
                        // build
                        script {

                            sh 'mvn -U -s $MAVEN_SETTINGS clean install'

                            try {
                                junit '**/target/surefire-reports/*.xml'
                            } catch (err) {
                                echo "No test report files were found. Skipping publishing of test reports."
                            }

                            withSonarQubeEnv('SonarQube') {
                                sh 'mvn -U -s $MAVEN_SETTINGS sonar:sonar'
                            }

                            sh "mvn -U -s $MAVEN_SETTINGS -Dbuild.date=\"$buildDate\" -Dgit.commit=\"$lastCommit\"  deploy"

                            stash includes: '**/target/**/*', name: 'target'
                        }
                    }
                }
            }

            stage("Build Docker image") {
                steps {

                    unstash 'target'

                    script {

                        //odstraniti ta blok, če je ok, da se premakne v init stage!!!
                        /***if (env.BRANCH_NAME == "develop" || env.BRANCH_NAME.startsWith("hotfix")) {applicationVersion = utils.getDockerTag(pom.version)
                         if (env.BRANCH_NAME.startsWith('hotfix')) {applicationVersion = applicationVersion + '-SNAPSHOT'}} else {applicationVersion = utils.getDockerTagWithBranch(pom.version)}**/

                        dockerImageName = dockerImageName + applicationVersion
                        def dockerImage = docker.build(dockerPushRegistry + dockerImageName)

                        docker.withRegistry('https://nexus-ci.kumuluz.com', 'nexus-ci-zvoneg') {
                            dockerImage.push()
                            dockerImage.push('latest')
                        }
                    }
                }
            }

            stage('Deploy to Docker Swarm') {

                steps {
                    // update docker-compose.yml
                    script {
                        String dockerComposeYml = 'docker-compose.yml'

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
            script {
                utils.postJob()
            }
        }
    }
}
