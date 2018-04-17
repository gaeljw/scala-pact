package com.itv.scalapact.plugin

import com.itv.scalapact.circe09._
import com.itv.scalapact.http4s18._
import com.itv.scalapact.plugin.publish.ScalaPactPublishCommand
import com.itv.scalapact.plugin.stubber.ScalaPactStubberCommand
import com.itv.scalapact.plugin.tester.ScalaPactTestCommand
import com.itv.scalapact.plugin.verifier.ScalaPactVerifyCommand
import com.itv.scalapact.shared.ScalaPactSettings
import sbt.Keys._
import sbt.plugins.JvmPlugin
import sbt.{Def, _}
import complete.DefaultParsers._

import scala.concurrent.duration.Duration

object ScalaPactPlugin extends AutoPlugin {
  override def requires: JvmPlugin.type = plugins.JvmPlugin
  override def trigger: PluginTrigger   = allRequirements

  object autoImport {
    val providerStateMatcher: SettingKey[PartialFunction[String, Boolean]] =
      SettingKey[PartialFunction[String, Boolean]]("provider-state-matcher",
                                                   "Alternative partial function for provider state setup")

    val providerStates: SettingKey[Seq[(String, (String) => Boolean)]] =
      SettingKey[Seq[(String, String => Boolean)]]("provider-states", "A list of provider state setup functions")

    val pactBrokerAddress: SettingKey[String] =
      SettingKey[String]("pactBrokerAddress", "The base url to publish / pull pact contract files to and from.")

    val providerBrokerPublishMap: SettingKey[Map[String, String]] =
      SettingKey[Map[String, String]](
        "providerBrokerPublishMap",
        "An optional map of this consumer's providers, and alternate pact brokers to publish those contracts to."
      )

    val providerName: SettingKey[String] =
      SettingKey[String]("providerName", "The name of the service to verify")

    val consumerNames: SettingKey[Seq[String]] =
      SettingKey[Seq[String]]("consumerNames", "The names of the services that consume the service to verify")

    val versionedConsumerNames: SettingKey[Seq[(String, String)]] =
      SettingKey[Seq[(String, String)]](
        "versionedConsumerNames",
        "The name and pact version numbers of the services that consume the service to verify"
      )

    val pactContractVersion: SettingKey[String] =
      SettingKey[String](
        "pactContractVersion",
        "The version number the pact contract will be published under. If missing or empty, the project version will be used."
      )

    val allowSnapshotPublish: SettingKey[Boolean] =
      SettingKey[Boolean]("allowSnapshotPublish",
                          "Flag to permit publishing of snapshot pact files to pact broker. Default is false.")

    val scalaPactEnv: SettingKey[ScalaPactEnv] =
      SettingKey[ScalaPactEnv]("scalaPactEnv", "Settings used to config the running of tasks and commands")

    // Tasks
    val pactPack: TaskKey[Unit]   = taskKey[Unit]("Pack up Pact contract files")
    val pactPush: InputKey[Unit]  = inputKey[Unit]("Push Pact contract files to Pact Broker")
    val pactCheck: InputKey[Unit] = inputKey[Unit]("Verify service based on consumer requirements")
    val pactStub: InputKey[Unit]  = inputKey[Unit]("Run stub service from Pact contract files")

    val pactTest: TaskKey[Unit]     = taskKey[Unit]("clean, compile, test and then pactPack")
    val pactPublish: InputKey[Unit] = inputKey[Unit]("pactTest and then pactPush")
    val pactVerify: InputKey[Unit]  = inputKey[Unit]("pactCheck")
    val pactStubber: InputKey[Unit] = inputKey[Unit]("pactTest and then pactStub")

  }

  import autoImport._

  private val pf: PartialFunction[String, Boolean] = { case (_: String) => false }

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  private val pactSettings = Seq(
    providerStateMatcher := pf,
    providerStates := Seq(),
    pactBrokerAddress := "",
    providerBrokerPublishMap := Map.empty[String, String],
    providerName := "",
    consumerNames := Seq.empty[String],
    versionedConsumerNames := Seq.empty[(String, String)],
    pactContractVersion := "",
    allowSnapshotPublish := false,
    scalaPactEnv := ScalaPactEnv.default
  )

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  override lazy val projectSettings: Seq[Def.Setting[
    _ >: Seq[(String, String => Boolean)] with Seq[(String, String)] with Boolean with ScalaPactEnv with Map[
      String,
      String
    ] with String with Seq[String] with PartialFunction[String, Boolean] with Task[Unit] with InputTask[Unit]
  ]] =
    pactSettings ++ Seq(
      pactPack := pactPackTask.value
    ) ++ Seq(
      pactPush := pactPushTask.evaluated,
      pactCheck := pactCheckTask.evaluated,
      pactStub := pactStubTask.evaluated
    )

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  lazy val pactPackTask: Def.Initialize[Task[Unit]] =
    Def.task {
      ScalaPactTestCommand.doPactPack(scalaPactEnv.value.toSettings)
    }

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  lazy val pactPushTask: Def.Initialize[InputTask[Unit]] =
    Def.inputTask {
      ScalaPactPublishCommand.doPactPublish(
        scalaPactEnv.value.toSettings + ScalaPactSettings.parseArguments(spaceDelimited("<arg>").parsed),
        pactBrokerAddress.value,
        providerBrokerPublishMap.value,
        version.value,
        pactContractVersion.value,
        allowSnapshotPublish.value
      )
    }

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  lazy val pactCheckTask: Def.Initialize[InputTask[Unit]] =
    Def.inputTask {
      ScalaPactVerifyCommand.doPactVerify(
        scalaPactEnv.value.toSettings + ScalaPactSettings.parseArguments(spaceDelimited("<arg>").parsed),
        providerStates.value,
        providerStateMatcher.value,
        pactBrokerAddress.value,
        version.value,
        providerName.value,
        consumerNames.value,
        versionedConsumerNames.value
      )
    }

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  lazy val pactStubTask: Def.Initialize[InputTask[Unit]] =
    Def.inputTask {
      ScalaPactStubberCommand.runStubber(
        scalaPactEnv.value.toSettings + ScalaPactSettings.parseArguments(spaceDelimited("<arg>").parsed),
        ScalaPactStubberCommand.interactionManagerInstance
      )
    }

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  lazy val pactTest: Def.Initialize[Task[Unit]] = Def.task {
    (clean in Compile).value
    (compile in Compile).value
    (test in Test).value
    (pactPack in Test).value
  }

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  lazy val pactPublish: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    pactTest.value
    pactPush.evaluated
  }

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  lazy val pactVerify: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    pactCheck.evaluated
  }

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  lazy val pactStubber: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    pactTest.value
    pactStub.evaluated
  }

}

case class ScalaPactEnv(protocol: Option[String],
                        host: Option[String],
                        port: Option[Int],
                        localPactFilePath: Option[String],
                        strictMode: Option[Boolean],
                        clientTimeout: Option[Duration],
                        outputPath: Option[String]) {

  def +(other: ScalaPactEnv): ScalaPactEnv =
    ScalaPactEnv.append(this, other)

  def withProtocol(protocol: String): ScalaPactEnv =
    this.copy(protocol = Option(protocol))

  def withHost(host: String): ScalaPactEnv =
    this.copy(host = Option(host))

  def withPort(port: Int): ScalaPactEnv =
    this.copy(port = Option(port))

  def withLocalPactFilePath(path: String): ScalaPactEnv =
    this.copy(localPactFilePath = Option(path))

  def enableStrictMode: ScalaPactEnv =
    this.copy(strictMode = Option(true))

  def disableStrictMode: ScalaPactEnv =
    this.copy(strictMode = Option(false))

  def withClientTimeOut(duration: Duration): ScalaPactEnv =
    this.copy(clientTimeout = Option(duration))

  def withOutputPath(outputPath: String): ScalaPactEnv =
    this.copy(outputPath = Option(outputPath))

  def toSettings: ScalaPactSettings =
    ScalaPactSettings(protocol, host, port, localPactFilePath, strictMode, clientTimeout, outputPath)

}

object ScalaPactEnv {

  def apply: ScalaPactEnv = default

  def default: ScalaPactEnv = ScalaPactEnv(None, None, None, None, None, None, None)

  def append(a: ScalaPactEnv, b: ScalaPactEnv): ScalaPactEnv =
    ScalaPactEnv(
      host = b.host.orElse(a.host),
      protocol = b.protocol.orElse(a.protocol),
      port = b.port.orElse(a.port),
      localPactFilePath = b.localPactFilePath.orElse(a.localPactFilePath),
      strictMode = b.strictMode.orElse(a.strictMode),
      clientTimeout = b.clientTimeout.orElse(a.clientTimeout),
      outputPath = b.outputPath.orElse(a.outputPath)
    )
}
