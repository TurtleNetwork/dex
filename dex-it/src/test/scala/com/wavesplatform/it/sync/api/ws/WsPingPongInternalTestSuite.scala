package com.wavesplatform.it.sync.api.ws

import com.typesafe.config.{Config, ConfigFactory}

class WsPingPongInternalTestSuite extends WsPingPongBaseSuite {

  override protected lazy val wsStreamUri = getWsInternalStreamUri(dex1)

  override protected val dexInitialSuiteConfig: Config = ConfigFactory
    .parseString(
      s"""TN.dex.web-sockets.internal-client-handler.health-check = {
         |  ping-interval = $pingInterval
         |  pong-timeout = $pongTimeout
         |}""".stripMargin
    )
    .withFallback(jwtPublicKeyConfig)

}
