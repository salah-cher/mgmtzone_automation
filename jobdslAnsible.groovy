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
    String repo_credentials = "SCH_usr_pass_github_private"
    String branch = "master"
    String playbook
    String ansible_ssh_key = "ansible_svc_acc"
    String ansible_vault_passwd = "vaultpassword"
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
    static void create(job, config) {
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

/*            for (item in config.parameters) {
                parameters {
                    if (isSecret(item)) {
                        nonStoredPasswordParam(item, 'Secret that gets passed to Ansible')
                    } else {
                        stringParam(item, '', 'Variable that gets passed to Ansible')
                    }
                }
            }
*/

            scm {
                git {
                    remote {
                        url(config.repo)
                        credentials(config.repo_credentials)
                    }
                    branch('$GIT_BRANCH')
                }
            }

            //steps {
            //    shell('${WORKSPACE}/Inventory.py --static > ${WORKSPACE}/Generated_Ansible_Inventory')
            //}

            steps {
                ansiblePlaybook(config.playbook) {
                inventoryPath('Generated_Ansible_Inventory')
                    // if (nonDisruptive) 
		//	{
                  //      limit('$Specific_VM*')
                    //}
                    credentialsId(config.ansible_ssh_key)
                    vaultCredentialsId(config.ansible_vault_passwd)
                    colorizedOutput(true)
                    /*
					for (item in config.parameters) 
					{
                        extraVars {
                            extraVar(item, "\$" + item, isSecret(item))
                        }
                    }
					*/
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

void createJobs() 


{
    def constr = new CustomClassLoaderConstructor(this.class.classLoader)
    def yaml = new Yaml(constr)

    // List all *.yml files
    def cwd = hudson.model.Executor.currentExecutor().currentWorkspace.absolutize()
    def configsGlobal = getConfigFiles(new FilePath(cwd, 'configs/'))
    //def configsDC     = getConfigFiles(new FilePath(cwd, 'jobdsl/configs/' + dataCenter))
    //def configsDCEnv  = getConfigFiles(new FilePath(cwd, 'jobdsl/configs/' + dataCenter + '/' + env))
    //def configFiles = configsGlobal + configsDC + configsDCEnv
    def configFiles = configsGlobal
    println configFiles.values().toString().replace(',', '\n')

    def nonDisruptive = false

    // Create/update a pull request job for each config file
    configFiles.values().each { file ->
        def projectConfig = yaml.loadAs(file.readToString(), ProjectConfig.class)

        // service user for rundeck
        

        // build vms list for non-disruptive deployment
        //String vmList = '"Please_select_VM":"Please select VM",'



        // create jenkins job
    DeployTemplate.create(job(projectConfig.project), projectConfig)
        
    }
}

// MAIN
//def (dataCenter, env) = getEnvironment()

createJobs()
