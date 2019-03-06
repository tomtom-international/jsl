/**
* artifactoryUploadDebianPackage
* Uploads the specified debian package to artifactory. Defaults to the navkit-apt-local repo in artifactory.navkit-pipeline.tt3.com
* Requires that a username-password credential exist under the name "artifactory-apt-local" with valid credentials to artficatory.
* filename - String deb package filename
* ubuntuVersion - String Version of Ubuntu package is for (i.e. trusty or xenial)
* path - String path to directory containing deb packages
* credentialsId - String credentials identifier
* artifactoryUrl - String URL of Artifactory
* artifactoryRepo - String artifactory debian repo
* arch - String architecture string
*/
def call(String filename, String ubuntuVersion, String path = ".", String credentialsId "artifactory-apt-local", String artifactoryUrl, String artifactoryRepo = "apt-local", String arch = "amd64") {
    String aptDistros = "deb.component=main;deb.distribution=$ubuntuVersion"
    withCredentials([usernamePassword(credentialsId: "$credentialsId", usernameVariable: "username", passwordVariable: "password")]) {
        sh "cd $path && curl --fail --user \"${username}:${password}\" -X PUT \"https://${artifactoryUrl}/artifactory/${artifactoryRepo}/pool/${filename};deb.architecture=${arch};${aptDistros}\" -T \"${filename}\""
    }
}
