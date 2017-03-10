#!/usr/bin/groovy
package com.redhat.connect


@Grab(group='org.apache.httpcomponents', module='httpclient', version='4.5.2')

import groovy.json.*
import org.apache.http.StatusLine
import org.apache.http.client.methods.*
import org.apache.http.entity.*
import org.apache.http.impl.client.*


/**
 * Class to use with a Jenkins Pipeline for the Red Hat ContainerZone
 * <p>
 *      Example usage:
 *      <code><pre class="groovyTestCase">
 *      node {
 * stage('get secret') {
 * openshift.withCluster() {
 * def secret = openshift.selector('secret/container-zone').object()
 * dockerCfg = secret.data.'.dockercfg'
 * }
 * }
 * stage('build') {
 * openshiftBuild(buildConfig: "${externalRegistryImageName}", showBuildLogs: 'true')
 * }
 * stage('create imagestreamtag') {
 * cz = new com.redhat.connect.ContainerZone(dockerCfg)
 * cz.setImageName(imageName)
 * cz.setImageTag(imageTag)
 *
 *
 * openshift.withCluster() {
 *
 * def imageStreamImport = cz.getImageStreamImport("${externalRegistryImageName}", true)
 * def createImportImage = openshift.create( imageStreamImport )
 *
 * def istagobj = openshift.selector("istag/${externalRegistryImageName}:${imageTag}").object()
 * cz.setDockerImageDigest(istagobj.image.metadata.name)
 * }
 * }
 * stage('waitforscan') {
 * cz.setUri(uri)
 * cz.waitForScan(20, 30)
 * }
 * stage('scanresults') {
 * def output = cz.getScanResults()
 * wrap([$class: 'AnsiColorBuildWrapper']) {
 * print(output)
 * }
 * }
 * }
 *      </pre></code>
 * @author Joseph Callen
 * @version 0.1
 *
 */
class ContainerZone implements Serializable {

    /* variables provided to class */
    private String projectId            // required
    private String secret               // required
    private String imageName            // optional - if OpenShift methods are not used
    private String dockerImageDigest    // required
    private String uri = "https://connect.redhat.com/api/container/scanResults"
    private String dockerRegistryHost   // required
    private String imageTag             // optional - if OpenShift methods are not used


    /* variable used in other methods */
    private HashMap scanResultsMap

    /*
     * Static variables below are to print ASCII characters
     * and colors to make the output easier to read.
     */
    private static final String CHECK = "\u2713"
    private static final String X = "\u274C"
    private static final String ANSI_RESET = "\u001B[0m"
    private static final String ANSI_BLACK = "\u001B[30m"
    private static final String ANSI_RED = "\u001B[31m"
    private static final String ANSI_GREEN = "\u001B[32m"

    /**
     * Constructor
     * @param dockerCfg
     * @param dockerImageDigest
     */
    ContainerZone(String dockerCfg, String dockerImageDigest = "") {
        // TODO: What if this is a /.docker/config.json?
        JsonSlurperClassic parser = new JsonSlurperClassic()

        /* Base64 decode the dockercfg, that returns a byte array.  Create a new
         * string from the array and parse the JSON string to a HashMap.
         */
        HashMap dockerCfgMap = (HashMap)parser.parseText(new String(dockerCfg.decodeBase64()))
        parser = null

        Set keys = dockerCfgMap.keySet()
        Integer size = (Integer) keys.size()

        if(size == 1) {
            this.dockerRegistryHost = keys[0]
        }
        else {
            throw new Exception("dockerCfgMap keySet should only be a size of one (1) and is ${size}")
        }

        this.secret = dockerCfgMap[this.dockerRegistryHost].password
        this.projectId = dockerCfgMap[this.dockerRegistryHost].username
        this.dockerImageDigest = dockerImageDigest
    }
    /**
     * Constructor
     * @param projectId
     * @param secret
     * @param dockerImageDigest
     */
    ContainerZone(String projectId, String secret, String dockerImageDigest) {
        this.projectId = projectId
        this.secret = secret
        this.dockerImageDigest = dockerImageDigest
    }

    void setProjectId(value) { this.@projectId = value }
    void setImageName(value) { this.@imageName = value }
    void setSecret(value) { this.@secret = value }
    void setDockerImageDigest(value) { this.@dockerImageDigest = value }
    void setUri(value) { this.@uri = value }
    void setImageTag(value) { this.@imageTag = value }


    String getProjectId() { return this.@projectId }
    String getSecret() { return  this.@secret }
    String getImageName() { return this.@imageName }
    String getImageTag() { return this.@imageTag }
    String getDockerImageDigest() { return this.@dockerImageDigest }
    String getUri() { return this.@uri }
    HashMap getScanResultsMap() { return this.@scanResultsMap }

    /* NOTE
     * https://github.com/codetojoy/talk_maritimedevcon_groovy/blob/master/exampleB_REST_Client/v2_groovy/RESTClient.groovy
     * http://stackoverflow.com/questions/37864542/jenkins-pipeline-notserializableexception-groovy-json-internal-lazymap
     * https://issues.jenkins-ci.org/browse/JENKINS-37629
     * http://stackoverflow.com/questions/36636017/jenkins-groovy-how-to-call-methods-from-noncps-method-without-ending-pipeline
     * Getter / Setter bug must use @
     * https://issues.jenkins-ci.org/browse/JENKINS-31484
     */

    /**
     * POST to uri, retrieves the contents, parses to HashMap.
     *
     * @param uri
     * @param jsonString
     * @return HashMap of the parsed JSON string from the API.
     */
    private static final HashMap getResponseMap(String uri, String jsonString) {

        CloseableHttpResponse response
        CloseableHttpClient client = HttpClientBuilder.create().build()
        HttpPost httpPost = new HttpPost(uri)
        httpPost.addHeader("content-type", "application/json")
        HashMap resultMap = new HashMap()

        try {
            httpPost.setEntity(new StringEntity(jsonString))
            response = client.execute(httpPost)

            StatusLine statusLine = response.getStatusLine()
            if (statusLine.getStatusCode() != 200) {
                println("getResponseMap Error: ${statusLine.getReasonPhrase()}")
                // TODO: return empty HashMap on error or throw an error
            }
            else {
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))
                String jsonResponse = bufferedReader.getText()
                // No longer need the reader or the response
                bufferedReader.close()
                response.close()
                /* The JsonSluperClassic must be used vs JsonSlurper
                 * since it returns a LazyMap which is not serializable.
                 */
                JsonSlurperClassic parser = new JsonSlurperClassic()
                resultMap = (HashMap) parser.parseText(jsonResponse)
                parser = null
            }
            return resultMap
        }
        catch (all) {
            println(all.toString())
            // TODO: Not sure this is recommended, probably should rethrow
            System.exit(1)
        }
        finally {
            /* TODO: since I am calling CloseableHttpClient.execute() multiple time
             * TODO: does it make more sense to leave the connection open?
             */
            client.close()
        }
    }
    /**
     * Provides a BuildConfig from an existing BuildConfig modifying the output for the
     * ContainerZone registry
     *
     * @param HashMap buildConfig
     * @return HashMap that represents the BuildConfig object
     */
    public HashMap getOpenShiftBuildConfig(HashMap buildConfig) {
        String fromName = "${this.dockerRegistryHost}/${this.projectId}/${this.imageName}:${this.imageTag}"
        try {
            HashMap outputMap = [
                    "to": [
                        "kind": "DockerImage",
                            "name": fromName
                    ]
            ]


            buildConfig.metadata.uid = null
            buildConfig.metadata.resourceVersion = null
            buildConfig.metadata.creationTimestamp = null

            buildConfig.spec.triggers = [:]
            buildConfig.spec.output = outputMap

            return buildConfig
        }
        catch (all) {
            println(all.toString())
            // TODO: Not sure this is recommended, probably should rethrow
            System.exit(1)
        }
    }


    /**
     * Provides a ImageStreamImport HashMap that will be used to extract the docker image digest.
     *
     * @param importName
     * @param insecure
     * @return HashMap that represents a ImageStreamImport object
     */
    public HashMap getImageStreamImport(String importName, Boolean insecure = false) {

        String fromName = "${this.dockerRegistryHost}/${this.projectId}/${this.imageName}:${this.imageTag}"
        HashMap importStreamImageMap = [
                "kind"      : "ImageStreamImport",
                "apiVersion": "v1",
                "metadata"  : [
                        "name": importName
                ],
                "spec"      : [
                        "import": true,
                        "images": [
                                [
                                        "from"        : [
                                                "kind": "DockerImage",
                                                "name": fromName
                                        ]   ,
                                        "to"          : [
                                                "name": "latest"
                                        ],
                                        "importPolicy": [
                                                "insecure": insecure
                                        ]
                                ]
                        ]
                ]
        ]
        return importStreamImageMap

    }
    /**
     * Based on timeout will wait for the scanResult from the connect containerzone api.  If the result
     * is not available before the timeout return false otherwise store the result in the class variable and return true.
     *
     * @param timeout
     * @param retry
     * @return boolean
     */
    public boolean waitForScan(int timeout=10, int retry=30) {
        long timeoutMilliseconds = (long)(timeout * 60 * 1000)
        int retries = (timeout * 60) / retry
        String jsonString = """
        {
            "secret": "${this.secret}",
            "pid": "${this.projectId}",            
            "docker_image_digest": "${this.dockerImageDigest}"
        }
        """
        println(jsonString)

        for (int i = 0; i < retries; i++) {
            /* sleep before returning the scanResults
             * I think it might be a timing issue where the image has been pushed to the registry
             * but whatever processes in the background does not recognize the image.
             */
            sleep((long)(timeoutMilliseconds/retries))

            this.scanResultsMap = getResponseMap(uri, jsonString)
            int size = scanResultsMap.size()

            println("resultMap.size(): ${size}")

            /* Problem: The API returned a initial object that had a size of 1
             * the further calls were 0.  Once the scan was available the size was 6.
             */

            if( size > 1 ) {
                return true
            }
        }

        /* there were no results before timeout */
        return false
    }

    /**
     * Iterates through assessment list creating easier to read output.
     *
     * @return HashMap
     */
    public HashMap getScanResults() {
        String requiredOutput = "${this.ANSI_RED} ***** Required for Certification ***** ${this.ANSI_RESET}\n"
        String optionalOutput = "${this.ANSI_RED} ***** Not Required for Certification (recommended) ***** ${this.ANSI_RESET}\n"
        String output = ""
        HashMap scanOutput = new HashMap()


        // TODO: Determine the right output and error if not successful
        // int size = this.scanResultsMap.size()
        // println("getScanResults scanResultsMap.size(): ${size}")


        try {
            def assessments = this.scanResultsMap["certifications"][0]["assessment"]

            for (int i = 0; i < (int) assessments.size(); i++) {
                HashMap assessment = (HashMap) assessments[i]

                /* Clean up of the assessment name
                 * Remove the underscore, "exists" and capitalize
                 */
                String name = assessment.name.replaceAll('_', ' ').minus(" exists").capitalize()

                // TODO: Find a better way for this...
                if ((boolean) assessment["required_for_certification"]) {
                    if ((boolean) assessment["value"]) {
                        requiredOutput += "${this.ANSI_GREEN} ${this.CHECK} ${name} ${this.ANSI_RESET}\n"
                    } else {
                        requiredOutput += "${this.ANSI_RED} ${this.X} ${name} ${this.ANSI_RESET}\n"
                    }
                } else {
                    if ((boolean) assessment["value"]) {
                        optionalOutput += "${this.ANSI_GREEN} ${this.CHECK} ${name} ${this.ANSI_RESET}\n"
                    } else {
                        optionalOutput += "${this.ANSI_RED} ${this.X} ${name} ${this.ANSI_RESET}\n"
                    }
                }
            }
            output = requiredOutput + optionalOutput
            scanOutput.put("output", output)
            scanOutput.put("success", (boolean) this.scanResultsMap["certifications"][0]["Successful"])
        }
        catch (all){
            println(all.toString())
            // TODO: Not sure this is recommended, probably should rethrow
            System.exit(1)
        }
        // println(output)
        return scanOutput
    }
}
