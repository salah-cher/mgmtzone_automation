@Grapes([
    @Grab(group='org.yaml', module='snakeyaml', version='1.20')
])
import hudson.FilePath
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.CustomClassLoaderConstructor

// Class to load the YAML
class ProjectConfig {
    String project
    String repo = "https://github.com/salah-cher/mgmtzone_automation.git"
    String repo_credentials = "github_Salah_private"
    String branch = "master"
    String playbook
    String ansible_ssh_key = "ansible_ssh_key"
    String ansible_vault_passwd = "ansible_vault_passwd"
    String server_type
    short servernum = 2
    String service_user
    ArrayList parameters = []
    Map<String, String> service_tools = [:]
}

// JobDSL Template
class DeployTemplate {
    static boolean isSecret(name) {
        return name.matches(".*(?i:key|pass|secret).*")
    }

    // create deployment job except rundeck
    static void create(job, config, nonDisruptive, vmList) {
        job.with {
            description("Deploy ${config.project}. This job is managed via JobDSL; any manual changes will be lost.")

            wrappers {
                preBuildCleanup()
                colorizeOutput()
            }

            logRotator {
                artifactDaysToKeep(7)
                daysToKeep(90)
            }

            parameters {
                stringParam('GIT_BRANCH', config.branch, 'Git Branch to be used for Ansible repo')
            }

            for (item in config.parameters) {
                parameters {
                    if (isSecret(item)) {
                        nonStoredPasswordParam(item, 'Secret that gets passed to Ansible')
                    } else {
                        stringParam(item, '', 'Variable that gets passed to Ansible')
                    }
                }
            }

            if (nonDisruptive) {
                parameters {
                    activeChoiceParam('Specific_VM') {
                        description('Name of the Specific VM to be deployed')
                        filterable(false)
                        choiceType('SINGLE_SELECT')
                        groovyScript {
                            script('return [' + vmList + ']')
                            fallbackScript('"Error in script"')
                        }
                    }
                }
            }

            scm {
                git {
                    remote {
                        url(config.repo)
                        credentials(config.repo_credentials)
                    }
                    branch('$GIT_BRANCH')
                }
            }

            steps {
                shell('${WORKSPACE}/Inventory.py --static > ${WORKSPACE}/Generated_Ansible_Inventory')
            }

            steps {
                ansiblePlaybook(config.playbook) {
                    inventoryPath('Generated_Ansible_Inventory')
                    if (nonDisruptive) {
                        limit('$Specific_VM*')
                    }
                    credentialsId(config.ansible_ssh_key)
                    vaultCredentialsId(config.ansible_vault_passwd)
                    colorizedOutput(true)
                    for (item in config.parameters) {
                        extraVars {
                            extraVar(item, "\$" + item, isSecret(item))
                        }
                    }
                }
            }
        }
    }

def getEnvironment() {
    def hostName = InetAddress.localHost.getHostName()
    String[] str
    splitHostName = hostName.split('-')
    def dataCenter = splitHostName[0]
    def env = splitHostName[2][-1..-1]
    switch (env) {
        case "p":
            env = "prod"
            break
        case "s":
            env = "stag"
            break
        case "d":
            env = "dev"
            break
        case "q":
            env = "qa"
            break
        default:
            env = "SOMETHING_WENT_WRONG"
            break
    }
    return [dataCenter, env]
}

def getConfigFiles(dir) {
    if (!dir.isDirectory()) {
        return [:]
    }
    def fileList = dir.list('*.yml')
    def fileMap = fileList.collectEntries {
        [it.name , it]
    }
    return fileMap
}

void createJobs(String dataCenter, String env) {
    def constr = new CustomClassLoaderConstructor(this.class.classLoader)
    def yaml = new Yaml(constr)

    // List all *.yml files
    def cwd = hudson.model.Executor.currentExecutor().currentWorkspace.absolutize()
    def configsGlobal = getConfigFiles(new FilePath(cwd, 'jobdsl/configs/'))
    def configsDC     = getConfigFiles(new FilePath(cwd, 'jobdsl/configs/' + dataCenter))
    def configsDCEnv  = getConfigFiles(new FilePath(cwd, 'jobdsl/configs/' + dataCenter + '/' + env))
    def configFiles = configsGlobal + configsDC + configsDCEnv
    println configFiles.values().toString().replace(',', '\n')

    def nonDisruptive = false

    // Create/update a pull request job for each config file
    configFiles.values().each { file ->
        def projectConfig = yaml.loadAs(file.readToString(), ProjectConfig.class)

        // service user for rundeck
        if (dataCenter == 'vagrant') {
            projectConfig.rundeck_service_user = 'vagrant'
        } else {
            projectConfig.rundeck_service_user = projectConfig.rundeck_service_user.replace('<dc>', dataCenter)
        }

        // build vms list for non-disruptive deployment
        String vmList = '"Please_select_VM":"Please select VM",'

        for (i=1; i<= projectConfig.servernum; i++){
            vmList += '"' + dataCenter + '-cmz-' + projectConfig.server_type + env[0..0] + '-00' + i +'":"' + dataCenter + '-cmz-' + projectConfig.server_type + env[0..0] + '-00' + i + '",'
        }
        vmList += '"*":"All VMs"'

        // create jenkins job
        if (file ==~ /^.*\/param-rundeck.yml$/ ) {
            DeployTemplate.createRundeckJob(job(projectConfig.project), projectConfig, dataCenter, env, vmList)
        } else if (file ==~ /^.*portal.*$/ || file ==~ /^.*ytestrunner2.*$/) {
            nonDisruptive = true
            DeployTemplate.create(job(projectConfig.project), projectConfig, nonDisruptive, vmList)
        } else if (file ==~ /^.*\/param-monitoring.yml$/ ) {
            DeployTemplate.createMonitoringJob(job(projectConfig.project), projectConfig, dataCenter)
        } else if (file ==~ /^.*\/param-manage-loadbalancer.yml$/) {
            def service_tools = projectConfig.service_tools
            println(service_tools.toString());
            servicetoolList = ''
            for (key in service_tools.keySet()) {
                servicetoolList += '"' + key + '",'
                servicevms = ''
                println(key)
                for (i = 1; i <= projectConfig.servernum; i++) {
                    servicevms += '"' + dataCenter + '-cmz-' + service_tools.get(key) + env[0..0] + '-00' + i + '",'
                }
                println(servicevms)
                projectConfig.service_tools.put(key, servicevms)
            }

            DeployTemplate.createManageLBJob(job(projectConfig.project), projectConfig, servicetoolList)
        } else {
            nonDisruptive = false
            DeployTemplate.create(job(projectConfig.project), projectConfig, nonDisruptive, vmList )
        }
    }
}

// MAIN
def (dataCenter, env) = getEnvironment()

createJobs(dataCenter, env)