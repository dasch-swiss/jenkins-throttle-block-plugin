# Jenkins Lockdown Plugin

This Jenkins plugin allows throttling jobs and/or pipeline blocks. It can also be used to throttle the initial SCM checkout.

### Usage
See the settings below.
Throttling can also be exited with the `Exit Throttle` build step.

This plugin can also be used in Pipelines:

```
enterThrottle {
  ...
}
```

Any code within the `enterThrottle` block becomes subject to throttling.

For example:
```
stages {
    stage('Checkout') {
      steps {
        enterThrottle {
          checkout scm
        }
      }
    }
  }
}
```

### Settings
|Setting|Use|
|-|-|
|Manage > Configure System > Timeout after exiting throttle (ms)|A timeout in milliseconds before another job that is subject to throttling and/or another `enterThrottle` block can run after another job or `enterThrottle` block has stopped throttling.|
|Job > Enter throttle before running|Begin throttling this job before it has started running. When checked, this effectively throttles the job queueing and initial SCM checkout.|
|Job > Exit throttle after checkout|If checked, the job will no longer be blocking other (throttled) jobs from running after its initial SCM checkout. Check this if you only want to throttle the initial SCM checkout, but not the rest of the job.|

### Permissions
None

### Development
Starting a development Jenkins instance with this plugin: `mvn hpi:run`

Building the plugin: `mvn package`
