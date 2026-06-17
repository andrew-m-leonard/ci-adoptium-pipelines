# Jenkins Automation Guide

## Overview

This guide explains how to create a fully automated, "throwaway-able" Jenkins instance where all configuration and jobs are defined as code. This approach ensures that Jenkins instances can be recreated from scratch at any time without manual configuration.

## Architecture

The automation uses three complementary technologies:

1. **Jenkins Configuration as Code (JCasC)** - Configures Jenkins itself (security, tools, plugins)
2. **Job DSL** - Creates and manages pipeline jobs from Groovy scripts
3. **Docker Compose** - Provides containerized Jenkins deployment

## Components

### 1. Jenkins Configuration as Code (JCasC)

**File**: [`ci/jenkins/jcasc/jenkins.yaml`](../ci/jenkins/jcasc/jenkins.yaml)

JCasC configures the entire Jenkins instance including:
- Security settings (users, authentication)
- Global tools (JDK, Maven, Git)
- Plugin configuration
- System properties
- Credentials

**Key Features**:
- No manual setup wizard
- Reproducible configuration
- Version controlled
- Environment variable support for secrets

**Environment Variables**:
```bash
JENKINS_ADMIN_PASSWORD=your-secure-password
GITHUB_TOKEN=your-github-token  # Optional, for private repos
```

### 2. Job DSL Scripts

**Directory**: [`ci/jenkins/job-dsl/`](../ci/jenkins/job-dsl/)

Job DSL scripts define all Jenkins jobs as Groovy code:

#### Seed Job (`seed-job.groovy`)
- Bootstrap job that creates all other jobs
- Self-updating (recreates itself from DSL)
- Polls SCM for changes to DSL scripts
- Automatically removes jobs no longer defined in DSL

#### Pipeline Jobs (`openjdk-build-pipeline.groovy`)
- Defines the main OpenJDK build pipeline job
- Creates version-specific variants (JDK 8, 11, 17, 21)
- Configures parameters, triggers, and log rotation
- Points to declarative Jenkinsfile in repository

### 3. Docker Deployment

**File**: [`ci/jenkins/docker-compose.yml`](../ci/jenkins/docker-compose.yml)

Provides containerized Jenkins with:
- Pre-installed plugins
- JCasC configuration mounted
- Persistent volumes for data
- Health checks
- Optional agent containers

**File**: [`ci/jenkins/plugins.txt`](../ci/jenkins/plugins.txt)

Lists all required Jenkins plugins with versions.

## Quick Start

### Option 1: Docker Compose (Recommended)

1. **Set environment variables**:
```bash
export JENKINS_ADMIN_PASSWORD="your-secure-password"
export GITHUB_TOKEN="your-github-token"  # Optional
```

2. **Start Jenkins**:
```bash
cd ci/jenkins
docker-compose up -d
```

3. **Access Jenkins**:
- URL: http://localhost:8080
- Username: `admin`
- Password: Value of `JENKINS_ADMIN_PASSWORD`

4. **Verify setup**:
- Jenkins should be fully configured via JCasC
- Seed job should be created automatically
- Run seed job to create all pipeline jobs

### Option 2: Manual Jenkins Installation

1. **Install Jenkins** with JDK 17

2. **Install required plugins**:
```bash
jenkins-plugin-cli --plugin-file ci/jenkins/plugins.txt
```

3. **Configure JCasC**:
```bash
export CASC_JENKINS_CONFIG=/path/to/ci/jenkins/jcasc/jenkins.yaml
export JENKINS_ADMIN_PASSWORD="your-secure-password"
```

4. **Start Jenkins**:
```bash
java -jar jenkins.war
```

5. **Create seed job manually**:
- Create new Freestyle job named "seed-job"
- Add "Process Job DSLs" build step
- Configure to use `ci/jenkins/job-dsl/seed-job.groovy`
- Run the job

## Job Management Workflow

### Creating New Jobs

1. **Add Job DSL script** to `ci/jenkins/job-dsl/`:
```groovy
pipelineJob('my-new-job') {
    displayName('My New Job')
    description('Job description')

    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url('https://github.com/adoptium/ci-adoptium-pipelines.git')
                    }
                    branch('*/main')
                }
            }
            scriptPath('ci/jenkins/Jenkinsfile.declarative')
        }
    }
}
```

2. **Commit and push** the DSL script

3. **Run seed job** (or wait for automatic SCM polling)

4. **New job is created** automatically

### Updating Existing Jobs

1. **Modify Job DSL script** in `ci/jenkins/job-dsl/`

2. **Commit and push** changes

3. **Run seed job** (or wait for automatic SCM polling)

4. **Job is updated** automatically

### Removing Jobs

1. **Delete Job DSL script** or remove job definition

2. **Commit and push** changes

3. **Run seed job**

4. **Job is removed** automatically (if `removeAction('DELETE')` is configured)

## Customization

### Adding Custom Configuration

Edit [`ci/jenkins/jcasc/jenkins.yaml`](../ci/jenkins/jcasc/jenkins.yaml):

```yaml
jenkins:
  systemMessage: "Your custom message"
  numExecutors: 4

  globalNodeProperties:
    - envVars:
        env:
          - key: "CUSTOM_VAR"
            value: "custom-value"
```

### Adding Plugins

Edit [`ci/jenkins/plugins.txt`](../ci/jenkins/plugins.txt):

```
my-plugin:latest
another-plugin:1.2.3
```

### Configuring Credentials

Add to JCasC configuration:

```yaml
credentials:
  system:
    domainCredentials:
      - credentials:
          - usernamePassword:
              scope: GLOBAL
              id: "my-credentials"
              username: "user"
              password: "${MY_PASSWORD}"
              description: "My credentials"
```

## Testing Pipeline Changes

### Local Testing

Use the local runner to test pipeline changes without Jenkins:

```bash
cd ci/local
python3 run-pipeline.py --config path/to/config.json
```

See [`ci/local/README.md`](../ci/local/README.md) for details.

### Jenkins Testing

1. **Create test job** using Job DSL:
```groovy
pipelineJob('test-pipeline') {
    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url('https://github.com/your-fork/ci-adoptium-pipelines.git')
                    }
                    branch('*/your-test-branch')
                }
            }
            scriptPath('ci/jenkins/Jenkinsfile.declarative')
        }
    }
}
```

2. **Run test job** with your changes

3. **Verify results**

4. **Merge to main** when ready

## Disaster Recovery

### Recreating Jenkins Instance

1. **Backup** (if needed):
```bash
docker-compose exec jenkins tar czf /tmp/jenkins-backup.tar.gz /var/jenkins_home
docker cp jenkins-openjdk-build:/tmp/jenkins-backup.tar.gz ./
```

2. **Destroy instance**:
```bash
docker-compose down -v
```

3. **Recreate instance**:
```bash
docker-compose up -d
```

4. **Verify**:
- Jenkins configured via JCasC
- Seed job created automatically
- Run seed job to recreate all pipeline jobs

### Restoring from Backup

1. **Start Jenkins**:
```bash
docker-compose up -d
```

2. **Restore data**:
```bash
docker cp jenkins-backup.tar.gz jenkins-openjdk-build:/tmp/
docker-compose exec jenkins tar xzf /tmp/jenkins-backup.tar.gz -C /
docker-compose restart jenkins
```

## Best Practices

### 1. Version Control Everything
- All Job DSL scripts in Git
- JCasC configuration in Git
- Plugin list in Git
- Never make manual changes in Jenkins UI

### 2. Use Environment Variables for Secrets
```yaml
credentials:
  system:
    domainCredentials:
      - credentials:
          - string:
              secret: "${MY_SECRET}"
```

### 3. Test Changes Locally First
- Use local runner for pipeline logic
- Test Job DSL scripts in test Jenkins instance
- Validate JCasC configuration before deploying

### 4. Document Custom Configuration
- Add comments to Job DSL scripts
- Document environment variables
- Maintain changelog for configuration changes

### 5. Regular Backups
```bash
# Automated backup script
#!/bin/bash
DATE=$(date +%Y%m%d-%H%M%S)
docker-compose exec jenkins tar czf /tmp/backup-${DATE}.tar.gz /var/jenkins_home
docker cp jenkins-openjdk-build:/tmp/backup-${DATE}.tar.gz ./backups/
```

## Troubleshooting

### Seed Job Fails

**Check DSL script syntax**:
```bash
# Validate Groovy syntax
groovy -c ci/jenkins/job-dsl/openjdk-build-pipeline.groovy
```

**Check Jenkins logs**:
```bash
docker-compose logs jenkins
```

### JCasC Configuration Not Applied

**Verify environment variables**:
```bash
docker-compose exec jenkins env | grep CASC
```

**Check JCasC logs**:
```bash
docker-compose logs jenkins | grep -i casc
```

**Validate YAML syntax**:
```bash
yamllint ci/jenkins/jcasc/jenkins.yaml
```

### Plugin Installation Fails

**Check plugin compatibility**:
- Verify plugin versions in `plugins.txt`
- Check Jenkins version compatibility
- Review plugin dependencies

**Manual plugin installation**:
```bash
docker-compose exec jenkins jenkins-plugin-cli --plugins plugin-name:version
```

## Migration from Existing Jenkins

### 1. Export Current Configuration

**Export jobs using Job DSL**:
1. Install Job DSL plugin
2. Create job to export existing jobs:
```groovy
Jenkins.instance.getAllItems(Job.class).each { job ->
    println "Job: ${job.name}"
    println job.getConfigFile().asString()
}
```

**Export configuration**:
1. Install JCasC plugin
2. Navigate to Manage Jenkins → Configuration as Code
3. Click "View Configuration"
4. Save to `jenkins.yaml`

### 2. Convert to Job DSL

Convert exported XML to Job DSL format:
```groovy
pipelineJob('converted-job') {
    // Convert XML elements to DSL
}
```

### 3. Test in New Instance

1. Deploy new Jenkins with automation
2. Verify all jobs created correctly
3. Test pipeline execution
4. Compare results with old instance

### 4. Cutover

1. Schedule maintenance window
2. Final backup of old Jenkins
3. Switch DNS/load balancer to new instance
4. Monitor for issues
5. Keep old instance for rollback

## References

- [Jenkins Configuration as Code](https://github.com/jenkinsci/configuration-as-code-plugin)
- [Job DSL Plugin](https://github.com/jenkinsci/job-dsl-plugin)
- [Jenkins Docker Image](https://hub.docker.com/r/jenkins/jenkins)
- [Pipeline Documentation](https://www.jenkins.io/doc/book/pipeline/)

## Support

For issues or questions:
1. Check troubleshooting section above
2. Review Jenkins logs
3. Consult Job DSL/JCasC documentation
4. Open issue in repository