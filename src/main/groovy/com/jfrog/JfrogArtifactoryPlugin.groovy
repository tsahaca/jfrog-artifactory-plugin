/**
 * @author Tanmay Saha
 * 
 * Artifactory User Plugin. This plugin has 2 main methods
 * 
 * 1. artifactoryCleanUp() which will clean up any Repository
 *    to keep the last number of builds specified by keepLatest.
 *
 * 2. deleteSnapshot() will be invoked by afterCreate() event-handler 
 *    in Storage events to delete the corresponding SNAPSHOT Build of a RELEASE
 *    
 *    Reference Artifactory User Plugin - https://www.jfrog.com/confluence/display/RTF/User+Plugins#UserPlugins-Storage
 *    
 */

package com.jfrog

import org.artifactory.repo.RepoPath
import org.artifactory.fs.ItemInfo
import org.artifactory.repo.RepoPathFactory


/**
 * 
 * Storing the configuration from JfrogArtifactoryService.properties file
 *
 */
config = new ConfigSlurper().parse(new File("${System.properties.'artifactory.home'}/etc/plugins/JfrogArtifactoryPlugin.properties").toURI().toURL())

enum Action {
    Delete, Archive
}

/**
 * For External executions via REST Request
 * 1. http://host:port/artifactory/api/plugins/execute/cleanup?params=repos=libs-snapshots
 * 2. curl -i -uadmin:password -X POST  http://host:port/artifactory/api/plugins/execute/cleanup?params=repos=libs-snapshots
 */
executions {
    cleanup() { params ->
        def repos = params['repos'] as String[]
        repos.each{
            log.info "Iterating Repo $it"
            if( config.releaseRepos.contains(it) ){
                deleteOrArchiveOldArtifacts(it, "com/jfrog",
                    config.keepLatest, config.keepDays, config.archiveRepo, Action.Archive, config.selectProjects)
            } else if( config.snapshotRepos.contains(it) ){
                deleteOrArchiveOldArtifacts(it, "com/jfrog",
                    config.keepLatest, config.keepDays, config.archiveRepo, Action.Delete, config.selectProjects)
            } else {
                log.info "No action defined for Repository $it"
            }
            
        }
        
    }
}

/**
 * Handling and manipulating Storage Events
 */
storage {
   /**
    * Handle after create events.
    *
    * Closure parameters:
    * item (org.artifactory.fs.ItemInfo) - the original item being created.
    */
     afterCreate { ItemInfo item ->
        String repoKey = item.getRepoKey()
        String relPath = item.getRelPath()
        /**
         * If a Release Build is cut,
         * repoKey is in the list of releaseRepos
         */
        if( config.releaseRepos.contains(repoKey) && item.isFolder() && 
                isPathInSelectedProjects(relPath, config.selectProjects )){
            log.info "Before deleting snapshot of $relPath" 
            deleteSnapshot(item, config.snapshotRepos as String[])
            String parentRelPath=item.getRepoPath().getParent().getPath()
            deleteOrArchiveOldArtifacts(repoKey, parentRelPath, 
                config.keepLatest, config.keepDays, config.archiveRepo, Action.Archive,config.selectProjects)
        }
      }

      beforeDelete { ItemInfo item ->
          log.info "Inside beforeDelete : Deleting $item.repoPath "
      }
}

/**
 * Will evaluate if the current Path in
 * the list of selected Projects
 * @param relPath
 * @param selectedProjects
 * @return
 */
private boolean isPathInSelectedProjects(String relPath, List<String> selectedProjects){
    bRet=false
    int size=selectedProjects.size()
    if( (size > 0) && selectedProjects.getAt(0).equals("*") ){
        bRet=true
    } else {
        for(count in 0..<size){
            String selectProject = selectedProjects.getAt(count)
            log.info "isPathInSelectedProjects():: Iterating Project $selectProject for Relative Path $relPath"
            if(relPath.indexOf(selectProject) > -1){
                bRet=true
                break
            }
        }
    }

    log.info "isPathInSelectedProjects() returning $bRet"
    return bRet
}


/**
 *  This function will go through each repoPath in a given Repo
 *  Keep the last keepLatest releases and move the older ones	
 *  to jfrog-archive which are older than keepDays
 *  
 *  @param repoKey of the Repository
 *  @param path of the item in the Repository
 *  @param keepLatest latest number of artifacts to keep
 *  @param keepDays   how days old an artifact can live in the Repository
 *  @param archiveRepo key of the archive Repository 
 *  @param action delete/archive
 */
private def deleteOrArchiveOldArtifacts(String repoKey, String path, 
    int keepLatest, int keepDays, String archiveRepo, Action action,
    List<String> selectedProjects){
	
    log.info "Getting Child Nodes under  $path  in repo $repoKey..."
	List<ItemInfo> paths = repositories.getChildren(RepoPathFactory.create(repoKey,path))
    int size = paths.size()
    
    log.info "# of Child Nodes "+ size
    /**
     *  If the last node is a folder, process each child 
     */
    if( paths.getAt(size-1).isFolder()){
        for(count in 0..<size){
            childRelPath=paths.getAt(count).getRelPath() 
            log.info "Child Name  "+ childRelPath
            deleteOrArchiveOldArtifacts(repoKey,childRelPath,keepLatest,keepDays,archiveRepo, action, selectedProjects)
        }
    } else if(size > (keepLatest+1) ){
        if( isPathInSelectedProjects(path, config.selectProjects) ){
            for(count in 0..<size-(keepLatest+1)){
                RepoPath repoPath =  paths.getAt(count).getRepoPath()
                switch(action) {
                    case [Action.Archive] :
                        log.info "Archive Candidate "+ repoPath
                        /**
                         *  If delete candidate older that keepDays
                         *  the delete it, otherwise keep it
                         */
                        if( anyChildOlderThan(repoPath,keepDays)){
                            log.info "Moving the Archive Candidate "+ repoPath + " to "+ archiveRepo
                            RepoPath targetRepoPath= RepoPathFactory.create(archiveRepo,repoPath.getPath())
                            repositories.move(repoPath,targetRepoPath)
                        } else {
                            log.info "Keeping the Archive Candidate "+repoPath
                        }
                        break;
                    case [Action.Delete] :
                        log.info "Delete Candidate "+ repoPath
                        repositories.delete(repoPath)
                        break;
                    default :
                        log.info "Not a valid Action"
                        break;
                }
            }
        }
   }
}


/**
 *  Delete the corresponding  SNAPSHOT Build  
 *  when a Release build is cut
 *  
 *  @param item being published in releases repo
 *  @param repos all possible snapshot repositories
 */
private def deleteSnapshot(ItemInfo item, String[] repos){
    String name = item.getName()
    String repoKey = item.getRepoKey()
    log.info "$item.relPath created in repo $repoKey"
    String snapShotRelPath = item.getRelPath()+"-SNAPSHOT"
    log.info "Corresponding snapshot " + snapShotRelPath 
    repos.each{
       log.info "Checking for existence of $snapShotRelPath in  repo $it"
       RepoPath path = RepoPathFactory.create(it,snapShotRelPath) 
       if( repositories.exists(path) ){
           log.info "$snapShotRelPath exists in  repo $it, so deleting it"
	       repositories.delete(path)
       }
    }
}

/**
 * will check if any artifact under the path 
 * is older than keepDays (180) days
 * 
 * @param path
 * @param keepDays
 * @return true or false
 */
private boolean anyChildOlderThan(RepoPath path,  int keepDays){
    boolean bRet=false
    log.info "anyChildOlderThan:: "+ path.getRepoKey() + "   " +  path.getPath()
    List<ItemInfo> paths = repositories.getChildren(RepoPathFactory.create(path.getRepoKey(),path.getPath()))
    def monthsUntil = Calendar.getInstance()
    int months = keepDays/30
    monthsUntil.add(Calendar.MONTH, -months)
    long monthsUntilTm = monthsUntil.getTimeInMillis()
    int size = paths.size()
    log.info "anyChildOlderThan:: size "+size 

    for(count in 0..<size){
       String name =  paths.getAt(count).getName()
       long lastModTime =  paths.getAt(count).getLastModified()
       
       if( lastModTime > monthsUntilTm ){
            log.info "$name younger  than $keepDays days" 
        } else {
            log.info "$name older  than $keepDays days" 
            bRet= true
            break
        } 
    }
    return bRet
} 
