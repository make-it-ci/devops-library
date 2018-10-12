
# Devops Library
This git repository contains a library of reusable [Jenkins Pipeline](https://jenkins.io/doc/book/pipeline/) steps and functions that can be used in your `Jenkinsfile` to help improve your Continuous Delivery pipeline.


## How to use this library
To use the functions in this library just add the following to the top of your `Jenkinsfile`:

```groovy
@Library('devops-library@master') _
```

That will use the master branch of this library. You can if you wish pick a specific tag or commit SHA of this repository too.
