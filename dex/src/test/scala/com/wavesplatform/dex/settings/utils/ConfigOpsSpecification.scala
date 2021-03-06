package com.wavesplatform.dex.settings.utils

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.MatcherSpecBase
import com.wavesplatform.dex.settings.toConfigOps
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ConfigOpsSpecification extends AnyWordSpecLike with Matchers with MatcherSpecBase {

  val config: Config = ConfigFactory.parseString(
    s"""
       |TN.dex {
       |  id = "matcher-1"
       |  user = "test-user",
       |  private {
       |    seed = "test-seed",
       |    password = "test-password",
       |    seed58 = "test"
       |  }
       |}""".stripMargin
  )

  "ConfigOps" should {

    "correctly filter keys" in {
      val filtered = config.withoutKeys(Set("seed"))

      filtered.getString("TN.dex.user") should be("test-user")
      filtered.getString("TN.dex.private.password") should be("test-password")
      filtered.getObject("TN.dex.private") should have size 1
    }
  }
}
