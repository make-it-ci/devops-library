package si.ci.jenkins

class Utilities implements Serializable {
    def script

    Utilities(script) { this.script = script }

    def check(pattern) {
        script.result = script.sh(script: "git log -1 | grep '${pattern}'", returnStatus: true)
        if (script.result == 0) {
            return "true"
        } else {
            script.print pattern + " not found in git commit message. Aborting."
            return "false"
        }
    }

    def postProcess() {
        if (script.env.CI_RUN == "false") {
            script.currentBuild.result = 'NOT_BUILT'
        }
    }

    def getDockerTagWithBranch(version) {
        return (version + "-" + script.env.BRANCH_NAME + "-" + script.env.BUILD_ID).replaceAll("#", "-").replaceAll("/", "-")
    }

    def getDockerTag(version) {
        return version + "-" + script.env.BUILD_ID
    }

    def getLastCommit() {
        return script.sh(script: 'git log -1 --pretty=%H', returnStdout: true).trim()
    }

    def getBeforeLastCommit() {
        return script.sh(script: 'git log -1 --skip 1 --pretty=%H', returnStdout: true).trim()
    }

    def getThirdLastCommit() {
        return script.sh(script: 'git log -1 --skip 2 --pretty=%H', returnStdout: true).trim()
    }

    String getOrganizationName() {
        return script.scm.getUserRemoteConfigs()[0].getUrl().tokenize('/')[2].toLowerCase()
    }

    String getGitHost() {
        def urlItems = script.scm.getUserRemoteConfigs()[0].getUrl().tokenize('/')
        return urlItems[0] + '//' + urlItems[1]
    }

    String getGitRepository() {
        return script.scm.getUserRemoteConfigs()[0].getUrl() - 'https://'
    }

    String getGitRepositoryName() {
        return script.scm.getUserRemoteConfigs()[0].getUrl().tokenize('/').last().split("\\.")[0]
    }

    def commitHashForBuild(build) {
        def scmAction = build?.actions.find { action -> action instanceof jenkins.scm.api.SCMRevisionAction }
        return scmAction?.revision?.hash
    }

    def getLastSuccessfulCommit() {
        def lastSuccessfulHash = null
        def lastSuccessfulBuild = script.currentBuild.rawBuild.getPreviousSuccessfulBuild()

        if (lastSuccessfulBuild) {
            lastSuccessfulHash = commitHashForBuild(lastSuccessfulBuild)
        }

        return lastSuccessfulHash
    }

    def getCiCdGoal(branch, path_mirror, url, gitCredentialsId) {
        if (gitCredentialsId != null) {
            script.withCredentials([script.usernamePassword(credentialsId: gitCredentialsId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {

                script.GIT_PASSWORD = URLEncoder.encode(script.GIT_PASSWORD, "UTF-8")

                url = "https://${script.GIT_USERNAME}:${script.GIT_PASSWORD}@$url"

                if (!script.fileExists(path_mirror)) {
                    script.echo "Directory $path_mirror doesn't exist, creating.."
                    script.sh("git clone --mirror $url mirror")
                }

                script.dir(path_mirror) {
                    script.sh("git fetch -f origin $branch:$branch")
                    script.sh("git symbolic-ref HEAD refs/heads/$branch")
                    def commitMsg = script.sh(script: "git log -1 --pretty=%s", returnStdout: true)

                    script.print "Commit Message: $commitMsg"

                    if (commitMsg.toString().contains('[ci-run]')) {
                        return '[ci-run]'
                    } else if (commitMsg.toString().contains('[ci-release]')) {
                        return '[ci-release]'
                    } else if (commitMsg.toString().contains('[ci-deploy]')) {
                        return '[ci-deploy]'
                    } else if (commitMsg.toString().contains('[ci-cancel]')) {
                        return '[ci-cancel]'
                    } else if (commitMsg.toString().contains('[ci-finish]')) {
                        return "[ci-finish]"
                    } else if (commitMsg.toString().contains('[ci-hotfix]')) {
                        return '[ci-hotfix]'
                    } else if (commitMsg.toString().contains('[ci-lib]')) {
                        return '[ci-lib]'
                    } else {
                        return ''
                    }
                }
            }
        }
    }

    def checkConsistency(String goal, String initialValidation) {
        //def existingReleases = script.sh(script: "git branch -r | grep 'release\\|hotfix' | wc -l", returnStdout: true)
        def existingReleases = script.sh(script: "git branch -r | grep 'origin/release\\|hotfix' | wc -l", returnStdout: true)

        script.print "Existing releases: $existingReleases"

        if (existingReleases.trim() != initialValidation) {
            script.currentBuild.result = 'FAILURE'
            if (initialValidation.equals("0")) {
                script.error("Aborting - $goal: There are existing release or hotfix branches on remote repository. Finalize existing release/hotfix in order to create a new one.")
            } else {
                script.error("Aborting - $goal: Multiple release/hotfix branches detected on remote.")
            }
        }

        //script.sh("git fetch origin")

        def upToDateMerge = script.sh(script: 'git merge origin/master', returnStdout: true)

        script.print "Up-to-date merge: $upToDateMerge"

        if (!upToDateMerge.trim().contains("Already up-to-date") && !upToDateMerge.trim().contains("Already up to date")) {
            script.currentBuild.result = 'FAILURE'
            if (initialValidation.equals("0")) {
                script.error("Aborting - $goal: Current version of develop branch is not synchronized with master branch.")
            } else {
                script.error("Aborting - $goal: The release branch is no longer synchronized with master branch due to changes on master since release branch was created.")
            }
        }
    }

    boolean isDeployCommit(String commitId) {
        def commitMsg = script.sh(script: "git log --format=%B -n 1 $commitId", returnStdout: true).trim()
        script.print "Commit message: $commitMsg"
        return commitMsg.contains("[ci-deploy]")
    }

    static String parseCommitMsg(String commitMsg) {
        def startIndex = commitMsg.indexOf('[')
        def endIndex = commitMsg.indexOf(']')

        def finishContent = commitMsg.substring(startIndex + 1, endIndex)

        return finishContent.toString().tokenize(':')[1].toString()
    }

    def finalizeRelease(String branchName, mavenVersion, String deployCommitId) {

        script.sh("git reset --hard ${deployCommitId}")

        def pom = script.readMavenPom file: 'pom.xml'
        mavenVersion = pom.version

        script.sh("git checkout master")
        script.sh("git pull")
        script.sh("git merge ${deployCommitId}")
        script.sh("git tag -a v${mavenVersion} -m \"Release v${mavenVersion}\"")
        script.sh("git push origin v${mavenVersion}")

        script.sh("git checkout develop")
        script.sh("git pull")
        try {
            script.sh("git merge ${deployCommitId}")
        } catch (Exception e) {

            def mergeResult = script.sh(script: "git status --short | wc -l", returnStdout: true)

            if (!mergeResult.trim().equals("0")) {
                script.sh("git commit -am \"Merged with ${branchName}, Check for CONFLICTS.\"")
            }
        }

        script.sh("git push origin --delete ${branchName}")
        script.sh("git push origin develop")
        script.sh("git push origin master")
    }

    def getCommiter() {

        return script.sh(script: 'git show -s --pretty=%an',
                returnStdout: true
        ).trim()

    }

    String increasePatchVersion(String mavenVersion) {
        def originalV = mavenVersion.tokenize('.')

        def major = originalV[0]
        def minor = originalV[1]
        def patch = Integer.parseInt(originalV[2]) + 1;

        return "${major}.${minor}.${patch}".toString()
    }

    String getMicroservicePipelineName() {
        if (script.env.CICDGOAL == '[ci-run]' && (script.env.BRANCH_NAME == 'develop' || script.env.BRANCH_NAME.startsWith("feature") || script.env.BRANCH_NAME.startsWith('hotfix'))) {
            return 'docker-microservice-pipeline'
        } else if (script.env.CICDGOAL == '[ci-release]' && script.env.BRANCH_NAME == 'develop') {
            return 'docker-microservice-prepare-release-pipeline'
        } else if (script.env.CICDGOAL == '[ci-deploy]' && (script.env.BRANCH_NAME.startsWith('release') || script.env.BRANCH_NAME.startsWith('hotfix'))) {
            return 'docker-microservice-deploy-pipeline'
        } else if (script.env.CICDGOAL == '[ci-cancel]' && (script.env.BRANCH_NAME.startsWith('release') || script.env.BRANCH_NAME.startsWith('hotfix'))) {
            return 'docker-microservice-cancel-pipeline'
        } else if (script.env.CICDGOAL == '[ci-finish]' && (script.env.BRANCH_NAME.startsWith('release') || script.env.BRANCH_NAME.startsWith('hotfix'))) {
            return 'docker-microservice-finish-pipeline'
        } else if (script.env.CICDGOAL == '[ci-hotfix]' && script.env.BRANCH_NAME == 'develop') {
            return 'docker-microservice-prepare-hotfix-pipeline'
        } else if (script.env.CICDGOAL == '[ci-lib]') {
            return 'docker-microservice-lib-pipeline'
        } else {
            return ''
        }
    }

    def postJob(status) {

        switch (status) {
            case 'SUCCESS':
                script.echo "The pipeline ${script.currentBuild.fullDisplayName} completed successfully."
                sendNotifications 'SUCCESS', script.env.CICDGOAL
                break
            case 'UNSTABLE':
                script.echo "The pipeline ${script.currentBuild.fullDisplayName} is unstable."
                sendNotifications 'UNSTABLE', script.env.CICDGOAL
                break
            case 'FAILED':
                script.echo "The pipeline ${script.currentBuild.fullDisplayName} failed."
                sendNotifications 'FAILED', script.env.CICDGOAL
                break
        }
    }
}
