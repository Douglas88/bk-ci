/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.dockerhost.services

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.BuildImageResultCallback
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.exception.UnauthorizedException
import com.github.dockerjava.api.model.AuthConfig
import com.github.dockerjava.api.model.AuthConfigurations
import com.github.dockerjava.api.model.BuildResponseItem
import com.github.dockerjava.api.model.Capability
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.PushResponseItem
import com.github.dockerjava.api.model.Statistics
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.InvocationBuilder
import com.github.dockerjava.core.command.LogContainerResultCallback
import com.github.dockerjava.core.command.PushImageResultCallback
import com.github.dockerjava.core.command.WaitContainerResultCallback
import com.github.dockerjava.okhttp.OkDockerHttpClient
import com.github.dockerjava.transport.DockerHttpClient
import com.tencent.devops.common.api.constant.CommonMessageCode
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.pipeline.type.docker.ImageType
import com.tencent.devops.common.web.mq.alert.AlertLevel
import com.tencent.devops.dispatch.docker.pojo.DockerHostBuildInfo
import com.tencent.devops.dockerhost.common.Constants
import com.tencent.devops.dockerhost.common.DockerExitCodeEnum
import com.tencent.devops.dockerhost.common.ErrorCodeEnum
import com.tencent.devops.dockerhost.config.DockerHostConfig
import com.tencent.devops.dockerhost.dispatch.AlertApi
import com.tencent.devops.dockerhost.dispatch.DockerHostBuildLogResourceApi
import com.tencent.devops.dockerhost.dispatch.DockerHostBuildResourceApi
import com.tencent.devops.dockerhost.docker.DockerBindLoader
import com.tencent.devops.dockerhost.docker.DockerEnvLoader
import com.tencent.devops.dockerhost.docker.DockerVolumeLoader
import com.tencent.devops.dockerhost.exception.ContainerException
import com.tencent.devops.dockerhost.pojo.CheckImageRequest
import com.tencent.devops.dockerhost.pojo.CheckImageResponse
import com.tencent.devops.dockerhost.pojo.DockerBuildParam
import com.tencent.devops.dockerhost.pojo.DockerRunParam
import com.tencent.devops.dockerhost.pojo.DockerRunPortBinding
import com.tencent.devops.dockerhost.utils.CommonUtils
import com.tencent.devops.dockerhost.utils.ENTRY_POINT_CMD
import com.tencent.devops.dockerhost.utils.RandomUtil
import com.tencent.devops.dockerhost.utils.SigarUtil
import com.tencent.devops.process.engine.common.VMUtils
import com.tencent.devops.store.pojo.image.enums.ImageRDTypeEnum
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct

@Suppress("ALL")
@Component
class DockerHostBuildService(
    private val dockerHostConfig: DockerHostConfig,
    private val environment: Environment,
    private val dockerHostBuildApi: DockerHostBuildResourceApi,
    private val dockerHostBuildLogResourceApi: DockerHostBuildLogResourceApi,
    private val alertApi: AlertApi
) : AbstractDockerHostBuildService(dockerHostConfig, dockerHostBuildApi) {

    companion object {
        private val logger = LoggerFactory.getLogger(DockerHostBuildService::class.java)
    }

    fun checkImage(
        buildId: String,
        checkImageRequest: CheckImageRequest,
        containerId: String?,
        containerHashId: String?
    ): Result<CheckImageResponse?> {
        logger.info("checkImage buildId: $buildId, checkImageRequest: $checkImageRequest")
        // 判断用户录入的镜像信息是否能正常拉取到镜像
        val imageName = checkImageRequest.imageName
        try {
            val pullImageResult = createPullImage(
                imageType = checkImageRequest.imageType,
                imageName = checkImageRequest.imageName,
                registryUser = checkImageRequest.registryUser,
                registryPwd = checkImageRequest.registryPwd,
                buildId = buildId,
                containerId = containerId,
                containerHashId = containerHashId
            )
            logger.info("pullImageResult: $pullImageResult")
            if (pullImageResult.isNotOk()) {
                return Result(pullImageResult.status, pullImageResult.message, null)
            }
        } catch (t: Throwable) {
            logger.warn("Fail to pull the image $imageName of build $buildId", t)
            log(buildId, "pull image fail，error is：${t.message}", containerId, containerHashId)
            return Result(CommonMessageCode.SYSTEM_ERROR.toInt(), t.message, null)
        }
        val dockerImageName = CommonUtils.normalizeImageName(checkImageRequest.imageName)
        // 查询镜像详细信息
        val imageInfo = httpLongDockerCli.inspectImageCmd(dockerImageName).exec()
        logger.info("imageInfo: $imageInfo")
        val checkImageResponse = CheckImageResponse(
            author = imageInfo.author,
            comment = imageInfo.comment,
            size = imageInfo.size!!,
            virtualSize = imageInfo.virtualSize,
            repoTags = imageInfo.repoTags!!
        )
        return Result(checkImageResponse)
    }

    override fun createContainer(dockerHostBuildInfo: DockerHostBuildInfo): String {
        val imageName = CommonUtils.normalizeImageName(dockerHostBuildInfo.imageName)
        // 执行docker pull
        createPullImage(dockerHostBuildInfo)

        // 执行docker run
        val containerId = createDockerRun(dockerHostBuildInfo, imageName)

        // 等待一段时间，检查一下agent是否正常启动
        waitAgentUp(dockerHostBuildInfo, containerId)

        return containerId
    }

    override fun stopContainer(dockerHostBuildInfo: DockerHostBuildInfo) {
        try {
            // docker stop
            val containerInfo = httpLongDockerCli.inspectContainerCmd(dockerHostBuildInfo.containerId).exec()
            if ("exited" != containerInfo.state.status) {
                httpLongDockerCli.stopContainerCmd(dockerHostBuildInfo.containerId).withTimeout(15).exec()
            }
        } catch (e: Throwable) {
            logger.error("Stop the container failed, containerId: ${dockerHostBuildInfo.containerId}, error msg: $e")
        }

        try {
            // docker rm
            httpLongDockerCli.removeContainerCmd(dockerHostBuildInfo.containerId).exec()
        } catch (e: Throwable) {
            logger.error("Stop the container failed, containerId: ${dockerHostBuildInfo.containerId}, error msg: $e")
        } finally {
            // 找出所有跟本次构建关联的dockerRun启动容器并停止容器
            val containerInfo = httpLongDockerCli.listContainersCmd().withStatusFilter(setOf("running")).exec()
            for (container in containerInfo) {
                try {
                    // logger.info("${dockerBuildInfo.buildId}|${dockerBuildInfo.vmSeqId} containerName: ${container.names[0]}")
                    val containerName = container.names[0]
                    if (containerName.contains(getDockerRunStopPattern(dockerHostBuildInfo))) {
                        logger.info("${dockerHostBuildInfo.buildId}|${dockerHostBuildInfo.vmSeqId} " +
                                "stop dockerRun container, containerId: ${container.id}")
                        httpLongDockerCli.stopContainerCmd(container.id).withTimeout(15).exec()
                    }
                } catch (e: Exception) {
                    logger.error("${dockerHostBuildInfo.buildId}|${dockerHostBuildInfo.vmSeqId} " +
                            "Stop dockerRun container failed, containerId: ${container.id}", e)
                }
            }
        }
    }

    private fun createDockerRun(dockerBuildInfo: DockerHostBuildInfo, imageName: String): String {
        try {
            // docker run
            val binds = DockerBindLoader.loadBinds(dockerBuildInfo)

/*            val blkioRateDeviceWirte = BlkioRateDevice()
                .withPath("/dev/sda")
                .withRate(dockerBuildInfo.dockerResource.blkioDeviceWriteBps)
            val blkioRateDeviceRead = BlkioRateDevice()
                .withPath("/dev/sda")
                .withRate(dockerBuildInfo.dockerResource.blkioDeviceReadBps)*/

            val containerName =
                "dispatch-${dockerBuildInfo.buildId}-${dockerBuildInfo.vmSeqId}-${RandomUtil.randomString()}"
            val container = httpLongDockerCli.createContainerCmd(imageName)
                .withName(containerName)
                .withCmd("/bin/sh", ENTRY_POINT_CMD)
                .withEnv(DockerEnvLoader.loadEnv(dockerBuildInfo))
                .withVolumes(DockerVolumeLoader.loadVolumes(dockerBuildInfo))
                .withHostConfig(HostConfig()
                    .withCapAdd(Capability.SYS_PTRACE)
                    .withMemory(dockerBuildInfo.dockerResource.memoryLimitBytes)
                    .withMemorySwap(dockerBuildInfo.dockerResource.memoryLimitBytes)
                    .withCpuQuota(dockerBuildInfo.dockerResource.cpuQuota.toLong())
                    .withCpuPeriod(dockerBuildInfo.dockerResource.cpuPeriod.toLong())
/*                    .withBlkioDeviceWriteBps(listOf(blkioRateDeviceWirte))
                    .withBlkioDeviceReadBps(listOf(blkioRateDeviceRead))*/
                    .withBinds(binds)
                    .withNetworkMode("bridge"))
                .exec()

            logger.info("Created container $container")
            httpLongDockerCli.startContainerCmd(container.id).exec()

            return container.id
        } catch (er: Throwable) {
            logger.error(er.toString())
            logger.error(er.message)
            log(
                buildId = dockerBuildInfo.buildId,
                red = true,
                message = "启动构建环境失败，错误信息:${er.message}",
                tag = VMUtils.genStartVMTaskId(dockerBuildInfo.vmSeqId.toString()),
                containerHashId = dockerBuildInfo.containerHashId
            )
            if (er is NotFoundException) {
                throw ContainerException(
                    errorCodeEnum = ErrorCodeEnum.IMAGE_NOT_EXIST_ERROR,
                    message = "构建镜像不存在"
                )
            } else {
                alertApi.alert(
                    AlertLevel.HIGH.name, "Docker构建机创建容器失败", "Docker构建机创建容器失败, " +
                            "母机IP:${CommonUtils.getInnerIP()}， 失败信息：${er.message}"
                )
                throw ContainerException(
                    errorCodeEnum = ErrorCodeEnum.CREATE_CONTAINER_ERROR,
                    message = "[${dockerBuildInfo.buildId}]|Create container failed"
                )
            }
        }
    }

    private fun waitAgentUp(dockerBuildInfo: DockerHostBuildInfo, containerId: String) {
        var exitCode = 0L
        try {
            // 等待5s，看agent是否正常启动
            Thread.sleep(5000)
            val containerState = getContainerState(containerId)
            logger.info("containerState: $containerState")
            if (containerState != null) {
                exitCode = containerState.exitCodeLong ?: 0L
            }
        } catch (e: Exception) {
            logger.error("[${dockerBuildInfo.buildId}]|[${dockerBuildInfo.vmSeqId}] waitAgentUp failed. containerId: $containerId", e)
        }

        if (exitCode != 0L && DockerExitCodeEnum.getValue(exitCode) != null) {
            val errorCodeEnum = DockerExitCodeEnum.getValue(exitCode)!!.errorCodeEnum
            logger.error("[${dockerBuildInfo.buildId}]|[${dockerBuildInfo.vmSeqId}] waitAgentUp failed. " +
                    "${errorCodeEnum.formatErrorMessage}. containerId: $containerId")
            throw ContainerException(
                errorCodeEnum = errorCodeEnum,
                message = "Failed to wait agent up. ${errorCodeEnum.formatErrorMessage}"
            )
        }
    }

    private fun getDockerRunStopPattern(dockerBuildInfo: DockerHostBuildInfo): String {
        // 用户取消操作
        return if (dockerBuildInfo.vmSeqId == 0) {
            "dockerRun-${dockerBuildInfo.buildId}"
        } else {
            "dockerRun-${dockerBuildInfo.buildId}-${dockerBuildInfo.vmSeqId}"
        }
    }

    fun getContainerNum(): Int {
        try {
            val dockerInfo = httpLongDockerCli.infoCmd().exec()
            return dockerInfo.containersRunning ?: 0
        } catch (e: Throwable) {
            logger.error("Get container num failed")
        }
        return 0
    }

    fun dockerBuildAndPushImage(
        projectId: String,
        pipelineId: String,
        vmSeqId: String,
        dockerBuildParam: DockerBuildParam,
        buildId: String,
        elementId: String?,
        outer: Boolean
    ): Pair<Boolean, String?> {
        lateinit var dockerClient: DockerClient
        try {
            val repoAddr = dockerBuildParam.repoAddr
            val userName = dockerBuildParam.userName
            val password = dockerBuildParam.password
            val config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerConfig(dockerHostConfig.dockerConfig)
                .withApiVersion(dockerHostConfig.apiVersion)
                .withRegistryUrl(repoAddr)
                .withRegistryUsername(userName)
                .withRegistryPassword(password)
                .build()

            val longHttpClient: DockerHttpClient = OkDockerHttpClient.Builder()
                .dockerHost(config.dockerHost)
                .sslConfig(config.sslConfig)
                .connectTimeout(5000)
                .readTimeout(300000)
                .build()

            dockerClient = DockerClientBuilder.getInstance(config).withDockerHttpClient(longHttpClient).build()
            val authConfig = AuthConfig()
                .withUsername(userName)
                .withPassword(password)
                .withRegistryAddress(repoAddr)

            val authConfigurations = AuthConfigurations()
            authConfigurations.addConfig(authConfig)

            val ticket = dockerBuildParam.ticket
            val args = dockerBuildParam.args
            ticket.forEach {
                val baseConfig = AuthConfig()
                    .withUsername(it.second)
                    .withPassword(it.third)
                    .withRegistryAddress(it.first)
                authConfigurations.addConfig(baseConfig)
            }

            val workspace = getWorkspace(pipelineId, vmSeqId.toInt(), dockerBuildParam.poolNo ?: "0")
            val buildDir = Paths.get(workspace + dockerBuildParam.buildDir).normalize().toString()
            val dockerfilePath = Paths.get(workspace + dockerBuildParam.dockerFile).normalize().toString()
            val baseDirectory = File(buildDir)
            val dockerfile = File(dockerfilePath)

            val imageNameTagSet = mutableSetOf<String>()
            if (dockerBuildParam.imageTagList.isNotEmpty()) {
                dockerBuildParam.imageTagList.forEach {
                    imageNameTagSet.add(getImageNameWithTag(
                        repoAddr = repoAddr,
                        projectId = projectId,
                        imageName = dockerBuildParam.imageName,
                        imageTag = it,
                        outer = outer
                    ))
                }
            } else {
                imageNameTagSet.add(getImageNameWithTag(
                    repoAddr = repoAddr,
                    projectId = projectId,
                    imageName = dockerBuildParam.imageName,
                    imageTag = dockerBuildParam.imageTag,
                    outer = outer
                ))
            }

            logger.info("Build docker image, workspace: $workspace, buildDir:$buildDir, dockerfile: $dockerfilePath")
            logger.info("Build docker image, imageNameTag: $imageNameTagSet")
            val step = dockerClient.buildImageCmd().withNoCache(true)
                .withPull(true)
                .withBuildAuthConfigs(authConfigurations)
                .withBaseDirectory(baseDirectory)
                .withDockerfile(dockerfile)
                .withTags(imageNameTagSet)
            args.map { it.trim().split("=") }.forEach {
                step.withBuildArg(it.first(), it.last())
            }
            step.exec(MyBuildImageResultCallback(buildId, elementId, dockerHostBuildApi))
                .awaitImageId()

            imageNameTagSet.parallelStream().forEach {
                logger.info("Build image success, now push to repo, image name and tag: $it")
                dockerClient.pushImageCmd(it)
                    .withAuthConfig(authConfig)
                    .exec(MyPushImageResultCallback(buildId, elementId, dockerHostBuildApi))
                    .awaitCompletion()

                logger.info("Push image success, now remove local image, image name and tag: $it")
                try {
                    httpLongDockerCli.removeImageCmd(it).exec()
                    logger.info("Remove local image success")
                } catch (e: Throwable) {
                    logger.error("Docker rmi failed, msg: ${e.message}")
                }
            }

            return Pair(true, null)
        } catch (e: Throwable) {
            logger.error("Docker build and push failed, exception: ", e)
            val cause = if (e.cause != null && e.cause!!.message != null) {
                e.cause!!.message!!.removePrefix(getWorkspace(pipelineId = pipelineId,
                    vmSeqId = vmSeqId.toInt(),
                    poolNo = dockerBuildParam.poolNo ?: "0")
                )
            } else {
                ""
            }

            return Pair(false, e.message + if (cause.isBlank()) "" else " cause:【$cause】")
        } finally {
            try {
                dockerClient.close()
            } catch (e: IOException) {
                logger.error("docker client close exception: ${e.message}")
            }
        }
    }

    fun dockerRun(
        projectId: String,
        pipelineId: String,
        vmSeqId: String,
        buildId: String,
        dockerRunParam: DockerRunParam
    ): Triple<String, Int, List<DockerRunPortBinding>> {
        try {
            val imageName = CommonUtils.normalizeImageName(dockerRunParam.imageName)
            // docker pull
            try {
                LocalImageCache.saveOrUpdate(imageName)
                createPullImage(
                    imageType = ImageType.THIRD.type,
                    imageName = dockerRunParam.imageName,
                    registryUser = dockerRunParam.registryUser,
                    registryPwd = dockerRunParam.registryPwd,
                    buildId = buildId,
                    containerId = vmSeqId,
                    containerHashId = ""
                )
            } catch (t: UnauthorizedException) {
                val errorMessage = "无权限拉取镜像：$imageName，请检查凭证"
                logger.error(errorMessage, t)
                // 直接失败，禁止使用本地镜像
                throw NotFoundException(errorMessage)
            } catch (t: NotFoundException) {
                val errorMessage = "仓库中镜像不存在：$imageName，请检查凭证"
                logger.error(errorMessage, t)
                // 直接失败，禁止使用本地镜像
                throw NotFoundException(errorMessage)
            } catch (t: Throwable) {
                logger.warn("[$buildId]|[$vmSeqId] Fail to pull the image $imageName of build $buildId", t, "")
                log(
                    buildId = buildId,
                    message = "拉取镜像失败，错误信息：${t.message}",
                    tag = VMUtils.genStartVMTaskId(vmSeqId),
                    containerHashId = ""
                )
                log(
                    buildId = buildId,
                    message = "尝试使用本地镜像执行命令...",
                    tag = VMUtils.genStartVMTaskId(vmSeqId),
                    containerHashId = ""
                )
            }

            val dockerBuildInfo = DockerHostBuildInfo(
                projectId = projectId,
                agentId = "",
                pipelineId = pipelineId,
                buildId = buildId,
                vmSeqId = vmSeqId.toInt(),
                secretKey = "",
                status = 0,
                imageName = imageName,
                containerId = "",
                wsInHost = true,
                poolNo = if (dockerRunParam.poolNo == null) 0 else dockerRunParam.poolNo!!.toInt(),
                registryUser = dockerRunParam.registryUser,
                registryPwd = dockerRunParam.registryPwd,
                imageType = ImageType.THIRD.type,
                imagePublicFlag = false,
                imageRDType = null,
                containerHashId = ""
            )
            // docker run
            val env = mutableListOf<String>()
            env.addAll(DockerEnvLoader.loadEnv(dockerBuildInfo))
            env.add("bk_devops_start_source=dockerRun") // dockerRun启动标识
            dockerRunParam.env?.forEach {
                env.add("${it.key}=${it.value ?: ""}")
            }
            logger.info("[$buildId]|[$vmSeqId] env is $env")
            val binds = DockerBindLoader.loadBinds(dockerBuildInfo)

            val dockerRunPortBindingList = mutableListOf<DockerRunPortBinding>()
            val hostIp = CommonUtils.getInnerIP()
            val portBindings = Ports()
            dockerRunParam.portList?.forEach {
                val localPort = getAvailableHostPort()
                if (localPort == 0) {
                    throw ContainerException(
                        errorCodeEnum = ErrorCodeEnum.NO_AVAILABLE_PORT_ERROR,
                        message = "No enough port to use in dockerRun. startPort: ${dockerHostConfig.dockerRunStartPort}"
                    )
                }
                val tcpContainerPort: ExposedPort = ExposedPort.tcp(it)
                portBindings.bind(tcpContainerPort, Ports.Binding.bindPort(localPort))
                dockerRunPortBindingList.add(DockerRunPortBinding(hostIp, it, localPort))
            }

            val containerName =
                "dockerRun-${dockerBuildInfo.buildId}-${dockerBuildInfo.vmSeqId}-${RandomUtil.randomString()}"

            val dockerResource = dockerHostBuildApi.getResourceConfig(pipelineId, vmSeqId)?.data

            val hostConfig: HostConfig
            if (dockerResource != null) {
                logger.info("[$buildId]|[$vmSeqId] dockerRun dockerResource: ${JsonUtil.toJson(dockerResource)}")
/*                val blkioRateDeviceWirte = BlkioRateDevice()
                    .withPath("/dev/sda")
                    .withRate(dockerResource.blkioDeviceWriteBps)
                val blkioRateDeviceRead = BlkioRateDevice()
                    .withPath("/dev/sda")
                    .withRate(dockerResource.blkioDeviceReadBps)*/

                hostConfig = HostConfig()
                    .withCapAdd(Capability.SYS_PTRACE)
                    .withBinds(binds)
                    .withMemory(dockerResource.memoryLimitBytes)
                    .withMemorySwap(dockerResource.memoryLimitBytes)
                    .withCpuQuota(dockerResource.cpuQuota.toLong())
                    .withCpuPeriod(dockerResource.cpuPeriod.toLong())
/*                        .withBlkioDeviceWriteBps(listOf(blkioRateDeviceWirte))
                        .withBlkioDeviceReadBps(listOf(blkioRateDeviceRead))*/
                    .withNetworkMode("bridge")
                    .withPortBindings(portBindings)
            } else {
                logger.info("[$buildId]|[$vmSeqId] dockerRun not config dockerResource.")
                hostConfig = HostConfig()
                    .withCapAdd(Capability.SYS_PTRACE)
                    .withBinds(binds)
                    .withNetworkMode("bridge")
                    .withPortBindings(portBindings)
            }

            val createContainerCmd = httpLongDockerCli.createContainerCmd(imageName)
                .withName(containerName)
                .withEnv(env)
                .withVolumes(DockerVolumeLoader.loadVolumes(dockerBuildInfo))
                .withHostConfig(hostConfig)
                .withWorkingDir(dockerHostConfig.volumeWorkspace)

            if (!(dockerRunParam.command.isEmpty() || dockerRunParam.command.equals("[]"))) {
                createContainerCmd.withCmd(dockerRunParam.command)
            }

            val container = createContainerCmd.exec()

            logger.info("[$buildId]|[$vmSeqId] Created container $container")
            val timestamp = (System.currentTimeMillis() / 1000).toInt()
            httpLongDockerCli.startContainerCmd(container.id).exec()

            return Triple(container.id, timestamp, dockerRunPortBindingList)
        } catch (er: Throwable) {
            val errorLog = "[$buildId]|[$vmSeqId]|启动容器失败，错误信息:${er.message}"
            logger.error(errorLog, er)
            log(buildId, true, errorLog, VMUtils.genStartVMTaskId(vmSeqId), "")
            alertApi.alert(
                level = AlertLevel.HIGH.name, title = "Docker构建机创建容器失败",
                message = "Docker构建机创建容器失败, 母机IP:${CommonUtils.getInnerIP()}， 失败信息：${er.message}"
            )
            throw ContainerException(
                errorCodeEnum = ErrorCodeEnum.CREATE_CONTAINER_ERROR,
                message = "启动容器失败，错误信息:${er.message}"
            )
        } finally {
            if (!dockerRunParam.registryUser.isNullOrEmpty()) {
                try {
                    httpLongDockerCli.removeImageCmd(dockerRunParam.imageName)
                    logger.info("[$buildId]|[$vmSeqId] Delete local image successfully......")
                } catch (e: java.lang.Exception) {
                    logger.info("[$buildId]|[$vmSeqId] the exception of deleteing local image is ${e.message}")
                } finally {
                    logger.info("[$buildId]|[$vmSeqId] Docker run end......")
                }
            }
        }
    }

    fun getDockerLogs(containerId: String, lastLogTime: Int): List<String> {
        val logs = ArrayList<String>()
        val logContainerCmd = httpLongDockerCli.logContainerCmd(containerId)
            .withStdOut(true)
            .withStdErr(true)
            .withSince(lastLogTime)
            .withTimestamps(true)
        try {
            logContainerCmd.exec(object : LogContainerResultCallback() {
                override fun onNext(item: Frame) {
                    logs.add(item.toString())
                }
            }).awaitCompletion()
        } catch (e: InterruptedException) {
            logger.error("Get docker run log exception: ", e)
        }
        return logs
    }

    fun getDockerRunExitCode(containerId: String): Int? {
        return try {
            httpLongDockerCli.waitContainerCmd(containerId)
                .exec(WaitContainerResultCallback())
                .awaitStatusCode(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.error("[$containerId]| getDockerRunExitCode error.", e)
            Constants.DOCKER_EXIST_CODE
        }
    }

    /**
     * 监控系统负载，超过一定阈值，对于占用负载较高的容器，主动降低负载
     */
    fun monitorSystemLoad() {
        logger.info("Monitor|cpu: ${SigarUtil.getAverageLongCpuLoad()}, mem: ${SigarUtil.getAverageLongMemLoad()}")
        if (SigarUtil.getAverageLongCpuLoad() > dockerHostConfig.elasticitySystemCpuThreshold ?: 80 ||
            SigarUtil.getAverageLongMemLoad() > dockerHostConfig.elasticitySystemMemThreshold ?: 80
        ) {
            checkContainerStats()
        }
    }

    private fun checkContainerStats() {
        val containerInfo = httpLongDockerCli.listContainersCmd().withStatusFilter(setOf("running")).exec()
        for (container in containerInfo) {
            val statistics = getContainerStats(container.id)
            if (statistics != null) {
                val systemCpuUsage = statistics.cpuStats.systemCpuUsage ?: 0
                val cpuUsage = statistics.cpuStats.cpuUsage!!.totalUsage ?: 0
                val preSystemCpuUsage = statistics.preCpuStats.systemCpuUsage ?: 0
                val preCpuUsage = statistics.preCpuStats.cpuUsage!!.totalUsage ?: 0
                val cpuUsagePer = if ((systemCpuUsage - preSystemCpuUsage) > 0) {
                    ((cpuUsage - preCpuUsage) * 100) / (systemCpuUsage - preSystemCpuUsage)
                } else {
                    0
                }

                // 优先判断CPU
                val elasticityCpuThreshold = dockerHostConfig.elasticityCpuThreshold ?: 80
                if (cpuUsagePer >= elasticityCpuThreshold) {
                    // 上报负载超额预警到数据平台
                    dockerHostBuildLogResourceApi.sendFormatLog(mapOf(
                        "containerName" to container.names[0],
                        "containerId" to container.id,
                        "cpuUsagePer" to cpuUsagePer.toString(),
                        "memUsagePer" to "",
                        "statistics" to JsonUtil.toJson(statistics)
                    ))

                    // 重置容器负载
                    resetContainer(container.id)
                    continue
                }

                if (statistics.memoryStats != null &&
                    statistics.memoryStats.usage != null &&
                    statistics.memoryStats.limit != null) {
                    val memUsage = statistics.memoryStats.usage!! * 100 / statistics.memoryStats.limit!!
                    val elasticityMemThreshold = dockerHostConfig.elasticityMemThreshold ?: 80
                    if (memUsage >= elasticityMemThreshold) {
                        // 上报负载超额预警到数据平台
                        dockerHostBuildLogResourceApi.sendFormatLog(mapOf(
                            "containerName" to container.names[0],
                            "containerId" to container.id,
                            "cpuUsagePer" to cpuUsagePer.toString(),
                            "memUsagePer" to memUsage.toString(),
                            "statistics" to JsonUtil.toJson(statistics)
                        ))

                        resetContainer(container.id)
                    }
                }
            }
        }
    }

    private fun getContainerStats(containerId: String): Statistics? {
        val asyncResultCallback = InvocationBuilder.AsyncResultCallback<Statistics>()
        httpDockerCli.statsCmd(containerId).withNoStream(true).exec(asyncResultCallback)
        return try {
            val stats = asyncResultCallback.awaitResult()
            stats
        } catch (e: Exception) {
            logger.error("containerId: $containerId get containerStats error.", e)
            null
        } finally {
            asyncResultCallback.close()
        }
    }

    private fun resetContainer(containerId: String) {
        val memReservation = dockerHostConfig.elasticityMemReservation ?: 32 * 1024 * 1024 * 1024L
        val cpuPeriod = dockerHostConfig.elasticityCpuPeriod ?: 10000
        val cpuQuota = dockerHostConfig.elasticityCpuQuota ?: 80000
        httpDockerCli.updateContainerCmd(containerId)
            .withMemory(memReservation)
            .withCpuPeriod(cpuPeriod)
            .withCpuQuota(cpuQuota).exec()
        logger.info("<<<< Trigger container reset, containerId: $containerId," +
            " memReservation: $memReservation, cpuPeriod: $cpuPeriod, cpuQuota: $cpuQuota")
    }

    fun clearContainers() {
        val containerInfo = httpLongDockerCli.listContainersCmd().withStatusFilter(setOf("exited")).exec()
        for (container in containerInfo) {
            try {
                val finishTime = httpLongDockerCli.inspectContainerCmd(container.id).exec().state.finishedAt
                // 是否已退出30分钟
                if (checkFinishTime(finishTime)) {
                    logger.info("Clear container, containerId: ${container.id}")
                    httpLongDockerCli.removeContainerCmd(container.id).exec()
                }
            } catch (e: Exception) {
                logger.error("Clear container failed, containerId: ${container.id}", e)
            }
        }
    }

    fun clearDockerRunTimeoutContainers() {
        val containerInfo = httpLongDockerCli.listContainersCmd().withStatusFilter(setOf("running")).exec()
        for (container in containerInfo) {
            try {
                val startTime = httpLongDockerCli.inspectContainerCmd(container.id).exec().state.startedAt
                val envs = httpLongDockerCli.inspectContainerCmd(container.id).exec().config.env
                // 是否是dockerRun启动的并且已运行超过8小时
                if (envs != null && envs.contains("bk_devops_start_source=dockerRun") && checkStartTime(startTime)) {
                    logger.info("Clear dockerRun timeout container, containerId: ${container.id}")
                    httpLongDockerCli.stopContainerCmd(container.id).withTimeout(15).exec()
                }
            } catch (e: Exception) {
                logger.error("Clear dockerRun timeout container failed, containerId: ${container.id}", e)
            }
        }
    }

    @PostConstruct
    fun loadLocalImages() {
        try {
            val imageList = httpLongDockerCli.listImagesCmd().withShowAll(true).exec()
            logger.info("load local images, image count: ${imageList.size}")
            imageList.forEach c@{
                it.repoTags?.forEach { image ->
                    LocalImageCache.saveOrUpdate(image)
                }
            }
        } catch (e: java.lang.Exception) {
            logger.error("load local image, exception, msg: ${e.message}")
        }
    }

    fun clearLocalImages() {
        val danglingImages = httpLongDockerCli.listImagesCmd().withDanglingFilter(true).withShowAll(true).exec()
        danglingImages.forEach {
            try {
                httpLongDockerCli.removeImageCmd(it.id).exec()
                logger.info("remove local dangling image success, image id: ${it.id}")
            } catch (e: java.lang.Exception) {
                logger.error("remove local dangling image exception ${e.message}")
            }
        }

        val publicImages = getPublicImages()
        val imageList = httpLongDockerCli.listImagesCmd().withShowAll(true).exec()
        imageList.forEach c@{
            if (it.repoTags == null || it.repoTags.isEmpty()) {
                return@c
            }
            it.repoTags.forEach t@{ image ->
                if (publicImages.contains(image)) {
                    logger.info("skip public image: $image")
                    return@t
                }

                val lastUsedDate = LocalImageCache.getDate(image)
                if (null != lastUsedDate) {
                    val days = TimeUnit.MILLISECONDS.toDays(Date().time - lastUsedDate.time)
                    if (days >= dockerHostConfig.localImageCacheDays) {
                        logger.info("remove local image, ${it.repoTags}")
                        try {
                            httpLongDockerCli.removeImageCmd(image).exec()
                            logger.info("remove local image success, image: $image")
                        } catch (e: java.lang.Exception) {
                            logger.error("remove local image exception ${e.message}")
                        }
                        return@c
                    }
                }
            }
        }
    }

    fun isContainerRunning(containerId: String): Boolean {
        try {
            logger.info("Check container: $containerId start.")
            val inspectContainerResponse = httpLongDockerCli.inspectContainerCmd(containerId).exec() ?: return false
            logger.info("Check container: $containerId status: ${inspectContainerResponse.state}")
            return inspectContainerResponse.state.running ?: false
        } catch (e: Exception) {
            logger.error("check container: $containerId status failed.", e)
            return false
        }
    }

    fun getContainerState(containerId: String): InspectContainerResponse.ContainerState? {
        try {
            logger.info("Get containerState: $containerId start.")
            val inspectContainerResponse = httpDockerCli.inspectContainerCmd(containerId).exec() ?: return null
            logger.info("Get containerState: $containerId state: ${inspectContainerResponse.state}")
            return inspectContainerResponse.state
        } catch (e: Exception) {
            logger.error("check container: $containerId state failed, return ", e)
            return null
        }
    }

    fun refreshDockerIpStatus(): Boolean? {
        val port = environment.getProperty("local.server.port")
        return dockerHostBuildApi.refreshDockerIpStatus(port!!, getContainerNum())!!.data
    }

    private fun getPublicImages(): List<String> {
        val result = mutableListOf<String>()
        val publicImages = dockerHostBuildApi.getPublicImages().data!!
        publicImages.filter { it.publicFlag && it.rdType == ImageRDTypeEnum.SELF_DEVELOPED }.forEach {
            result.add("${it.repoUrl}/${it.repoName}:${it.repoTag}")
        }
        return result
    }

    private fun getImageNameWithTag(
        repoAddr: String,
        projectId: String,
        imageName: String,
        imageTag: String,
        outer: Boolean = false
    ): String {
        return if (outer) {
            "$repoAddr/$imageName:$imageTag"
        } else {
            "$repoAddr/paas/$projectId/$imageName:$imageTag"
        }
    }

    private fun getWorkspace(pipelineId: String, vmSeqId: Int, poolNo: String): String {
        return "${dockerHostConfig.hostPathWorkspace}/$pipelineId/${getTailPath(vmSeqId, poolNo.toInt())}/"
    }

    private fun getTailPath(vmSeqId: Int, poolNo: Int): String {
        return if (poolNo > 1) {
            "$vmSeqId" + "_$poolNo"
        } else {
            vmSeqId.toString()
        }
    }

    private fun checkFinishTime(utcTime: String?): Boolean {
        if (utcTime != null && utcTime.isNotEmpty()) {
            val array = utcTime.split(".")
            val utcTimeLocal = array[0] + "Z"
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
            sdf.timeZone = TimeZone.getTimeZone("UTC")

            val date = sdf.parse(utcTimeLocal)
            val finishTimestamp = date.time
            val nowTimestamp = System.currentTimeMillis()
            return (nowTimestamp - finishTimestamp) > (30 * 60 * 1000)
        }

        return true
    }

    private fun checkStartTime(utcTime: String?): Boolean {
        if (utcTime != null && utcTime.isNotEmpty()) {
            val array = utcTime.split(".")
            val utcTimeLocal = array[0] + "Z"
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
            sdf.timeZone = TimeZone.getTimeZone("UTC")

            val date = sdf.parse(utcTimeLocal)
            val startTimestamp = date.time
            val nowTimestamp = System.currentTimeMillis()
            return (nowTimestamp - startTimestamp) > (8 * 3600 * 1000)
        }

        return false
    }

    private fun getAvailableHostPort(): Int {
        val startPort = dockerHostConfig.dockerRunStartPort ?: 20000
        for (i in startPort..(startPort + 1000)) {
            if (!CommonUtils.isPortUsing("127.0.0.1", i)) {
                return i
            } else {
                continue
            }
        }

        return 0
    }

    inner class MyBuildImageResultCallback internal constructor(
        private val buildId: String,
        private val elementId: String?,
        private val dockerHostBuildApi: DockerHostBuildResourceApi
    ) : BuildImageResultCallback() {
        override fun onNext(item: BuildResponseItem?) {
            val text = item?.stream
            if (null != text) {
                dockerHostBuildApi.postLog(
                    buildId,
                    false,
                    StringUtils.removeEnd(text, "\n"),
                    elementId
                )
            }

            super.onNext(item)
        }
    }

    inner class MyPushImageResultCallback internal constructor(
        private val buildId: String,
        private val elementId: String?,
        private val dockerHostBuildApi: DockerHostBuildResourceApi
    ) : PushImageResultCallback() {
        private val totalList = mutableListOf<Long>()
        private val step = mutableMapOf<Int, Long>()
        override fun onNext(item: PushResponseItem?) {
            val text = item?.progressDetail
            if (null != text && text.current != null && text.total != null && text.total != 0L) {
                val lays = if (!totalList.contains(text.total!!)) {
                    totalList.add(text.total!!)
                    totalList.size + 1
                } else {
                    totalList.indexOf(text.total!!) + 1
                }
                var currentProgress = text.current!! * 100 / text.total!!
                if (currentProgress > 100) {
                    currentProgress = 100
                }
                if (currentProgress >= step[lays]?.plus(25) ?: 5) {
                    dockerHostBuildApi.postLog(
                        buildId,
                        false,
                        "正在推送镜像,第${lays}层，进度：$currentProgress%",
                        elementId
                    )
                    step[lays] = currentProgress
                }
            }
            super.onNext(item)
        }
    }
}
