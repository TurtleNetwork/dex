package com.wavesplatform.it.sync.matcher

import com.typesafe.config.Config
import com.wavesplatform.it.ReportingTestName
import com.wavesplatform.it.api.AssetDecimalsInfo
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.api.SyncMatcherHttpApi._
import com.wavesplatform.it.sync.CustomFeeTransactionSuite.defaultAssetQuantity
import com.wavesplatform.it.sync._
import com.wavesplatform.it.sync.matcher.config.MatcherPriceAssetConfig._
import com.wavesplatform.it.transactions.NodesFromDocker
import com.wavesplatform.it.util._
import com.wavesplatform.matcher.model.LimitOrder
import com.wavesplatform.transaction.assets.exchange.OrderType.{BUY, SELL}
import com.wavesplatform.transaction.assets.exchange.{Order, OrderType}
import org.scalatest.{BeforeAndAfterAll, CancelAfterFailure, FreeSpec, Matchers}

import scala.concurrent.duration._
import scala.math.BigDecimal.RoundingMode

class TradeBalanceAndRoundingTestSuite
    extends FreeSpec
    with Matchers
    with BeforeAndAfterAll
    with CancelAfterFailure
    with NodesFromDocker
    with ReportingTestName {

  override protected def nodeConfigs: Seq[Config] = Configs

  private def matcherNode = nodes.head

  private def aliceNode = nodes(1)

  private def bobNode = nodes(2)

  Seq(IssueUsdTx, IssueEthTx, IssueWctTx).map(createSignedIssueRequest).foreach(matcherNode.signedIssue)
  nodes.waitForHeightArise()

  "Alice and Bob trade WAVES-USD" - {
    nodes.waitForHeightArise()
    val aliceWavesBalanceBefore = matcherNode.accountBalances(aliceNode.address)._1
    val bobWavesBalanceBefore   = matcherNode.accountBalances(bobNode.address)._1

    val price           = 238
    val buyOrderAmount  = 425532L
    val sellOrderAmount = 3100000000L

    val correctedSellAmount = correctAmount(sellOrderAmount, price)

    val adjustedAmount = receiveAmount(OrderType.BUY, price, buyOrderAmount)
    val adjustedTotal  = receiveAmount(OrderType.SELL, price, buyOrderAmount)

    log.debug(s"correctedSellAmount: $correctedSellAmount, adjustedAmount: $adjustedAmount, adjustedTotal: $adjustedTotal")

    "place usd-waves order" in {
      // Alice wants to sell USD for Waves

      val bobOrder1   = matcherNode.prepareOrder(bobNode, wavesUsdPair, OrderType.SELL, price, sellOrderAmount)
      val bobOrder1Id = matcherNode.placeOrder(bobOrder1).message.id
      matcherNode.waitOrderStatus(wavesUsdPair, bobOrder1Id, "Accepted", 1.minute)
      matcherNode.reservedBalance(bobNode)("WAVES") shouldBe sellOrderAmount + matcherFee
      matcherNode.tradableBalance(bobNode, wavesUsdPair)("WAVES") shouldBe bobWavesBalanceBefore - (sellOrderAmount + matcherFee)

      val aliceOrder   = matcherNode.prepareOrder(aliceNode, wavesUsdPair, OrderType.BUY, price, buyOrderAmount)
      val aliceOrderId = matcherNode.placeOrder(aliceOrder).message.id
      matcherNode.waitOrderStatusAndAmount(wavesUsdPair, aliceOrderId, "Filled", Some(420169L), 1.minute)

      // Bob wants to buy some USD
      matcherNode.waitOrderStatusAndAmount(wavesUsdPair, bobOrder1Id, "PartiallyFilled", Some(420169L), 1.minute)

      // Each side get fair amount of assets
      val exchangeTx = matcherNode.transactionsByOrder(aliceOrder.idStr()).headOption.getOrElse(fail("Expected an exchange transaction"))
      nodes.waitForHeightAriseAndTxPresent(exchangeTx.id)
    }

    "get opened trading markets. USD price-asset " in {
      val openMarkets = matcherNode.tradingMarkets()
      openMarkets.markets.size shouldBe 1
      val markets = openMarkets.markets.head

      markets.amountAssetName shouldBe "WAVES"
      markets.amountAssetInfo shouldBe Some(AssetDecimalsInfo(8))

      markets.priceAssetName shouldBe usdAssetName
      markets.priceAssetInfo shouldBe Some(AssetDecimalsInfo(Decimals))
    }

    "check usd and waves balance after fill" in {
      val aliceWavesBalanceAfter = matcherNode.accountBalances(aliceNode.address)._1
      val aliceUsdBalance        = matcherNode.assetBalance(aliceNode.address, UsdId.base58).balance

      val bobWavesBalanceAfter = matcherNode.accountBalances(bobNode.address)._1
      val bobUsdBalance        = matcherNode.assetBalance(bobNode.address, UsdId.base58).balance

      (aliceWavesBalanceAfter - aliceWavesBalanceBefore) should be(
        adjustedAmount - (BigInt(matcherFee) * adjustedAmount / buyOrderAmount).bigInteger.longValue())

      aliceUsdBalance - defaultAssetQuantity should be(-adjustedTotal)
      bobWavesBalanceAfter - bobWavesBalanceBefore should be(
        -adjustedAmount - (BigInt(matcherFee) * adjustedAmount / sellOrderAmount).bigInteger.longValue())
      bobUsdBalance should be(adjustedTotal)
    }

    "check filled amount and tradable balance" in {
      val bobsOrderId  = matcherNode.fullOrderHistory(bobNode).head.id
      val filledAmount = matcherNode.orderStatus(bobsOrderId, wavesUsdPair).filledAmount.getOrElse(0L)

      filledAmount shouldBe adjustedAmount
    }

    "check reserved balance" in {
      val reservedFee = BigInt(matcherFee) - (BigInt(matcherFee) * adjustedAmount / sellOrderAmount)
      log.debug(s"reservedFee: $reservedFee")
      val expectedBobReservedBalance = correctedSellAmount - adjustedAmount + reservedFee
      matcherNode.reservedBalance(bobNode)("WAVES") shouldBe expectedBobReservedBalance

      matcherNode.reservedBalance(aliceNode) shouldBe empty
    }

    "check waves-usd tradable balance" in {
      val expectedBobTradableBalance = bobWavesBalanceBefore - (correctedSellAmount + matcherFee)
      matcherNode.tradableBalance(bobNode, wavesUsdPair)("WAVES") shouldBe expectedBobTradableBalance
      matcherNode.tradableBalance(aliceNode, wavesUsdPair)("WAVES") shouldBe aliceNode.accountBalances(aliceNode.address)._1

      val orderId = matcherNode.fullOrderHistory(bobNode).head.id
      matcherNode.fullOrderHistory(bobNode).size should be(1)
      matcherNode.cancelOrder(bobNode, wavesUsdPair, Some(orderId))
      matcherNode.waitOrderStatus(wavesUsdPair, orderId, "Cancelled", 1.minute)
      matcherNode.tradableBalance(bobNode, wavesUsdPair)("WAVES") shouldBe bobNode.accountBalances(bobNode.address)._1
    }
  }

  "Alice and Bob trade WAVES-USD check CELLING" - {
    val price2           = 289
    val buyOrderAmount2  = 0.07.waves
    val sellOrderAmount2 = 3.waves

    val correctedSellAmount2 = correctAmount(sellOrderAmount2, price2)

    "place usd-waves order" in {
      nodes.waitForHeightArise()
      // Alice wants to sell USD for Waves
      val bobWavesBalanceBefore = matcherNode.accountBalances(bobNode.address)._1
      matcherNode.tradableBalance(bobNode, wavesUsdPair)("WAVES")
      val bobOrder1   = matcherNode.prepareOrder(bobNode, wavesUsdPair, OrderType.SELL, price2, sellOrderAmount2)
      val bobOrder1Id = matcherNode.placeOrder(bobOrder1).message.id
      matcherNode.waitOrderStatus(wavesUsdPair, bobOrder1Id, "Accepted", 1.minute)

      matcherNode.reservedBalance(bobNode)("WAVES") shouldBe correctedSellAmount2 + matcherFee
      matcherNode.tradableBalance(bobNode, wavesUsdPair)("WAVES") shouldBe bobWavesBalanceBefore - (correctedSellAmount2 + matcherFee)

      val aliceOrder   = matcherNode.prepareOrder(aliceNode, wavesUsdPair, OrderType.BUY, price2, buyOrderAmount2)
      val aliceOrderId = matcherNode.placeOrder(aliceOrder).message.id
      matcherNode.waitOrderStatus(wavesUsdPair, aliceOrderId, "Filled", 1.minute)

      // Bob wants to buy some USD
      matcherNode.waitOrderStatus(wavesUsdPair, bobOrder1Id, "PartiallyFilled", 1.minute)

      // Each side get fair amount of assets
      val exchangeTx = matcherNode.transactionsByOrder(aliceOrder.idStr()).headOption.getOrElse(fail("Expected an exchange transaction"))
      nodes.waitForHeightAriseAndTxPresent(exchangeTx.id)
      matcherNode.cancelOrder(bobNode, wavesUsdPair, Some(bobOrder1Id))
    }

  }

  "Alice and Bob trade WCT-USD sell price less than buy price" - {
    "place wcd-usd order corrected by new price sell amount less then initial one" in {
      val buyPrice   = 247700
      val sellPrice  = 135600
      val buyAmount  = 46978
      val sellAmount = 56978

      val bobOrderId = matcherNode.placeOrder(bobNode, wctUsdPair, SELL, sellPrice, sellAmount).message.id
      matcherNode.waitOrderStatus(wctUsdPair, bobOrderId, "Accepted", 1.minute)
      val aliceOrderId = matcherNode.placeOrder(aliceNode, wctUsdPair, BUY, buyPrice, buyAmount).message.id
      matcherNode.waitOrderStatus(wctUsdPair, aliceOrderId, "Filled", 1.minute)

      val exchangeTx = matcherNode.transactionsByOrder(aliceOrderId).headOption.getOrElse(fail("Expected an exchange transaction"))
      nodes.waitForHeightAriseAndTxPresent(exchangeTx.id)
      matcherNode.cancelOrder(bobNode, wctUsdPair, Some(bobOrderId))

      matcherNode.waitOrderStatus(wctUsdPair, bobOrderId, "Cancelled", 1.minute)

      matcherNode.reservedBalance(bobNode) shouldBe empty
      matcherNode.reservedBalance(aliceNode) shouldBe empty
    }
  }

  "Alice and Bob trade WCT-USD 1" - {
    val wctUsdSellAmount = 347
    val wctUsdBuyAmount  = 146
    val wctUsdPrice      = 12739213

    "place wct-usd order" in {
      nodes.waitForSameBlockHeadesAt(nodes.map(_.height).max + 1)

      val aliceUsdBalance   = matcherNode.assetBalance(aliceNode.address, UsdId.base58).balance
      val bobUsdBalance     = matcherNode.assetBalance(bobNode.address, UsdId.base58).balance
      val bobWctInitBalance = matcherNode.assetBalance(bobNode.address, WctId.base58).balance

      val bobOrderId = matcherNode.placeOrder(bobNode, wctUsdPair, SELL, wctUsdPrice, wctUsdSellAmount).message.id
      matcherNode.waitOrderStatus(wctUsdPair, bobOrderId, "Accepted", 1.minute)

      val aliceOrderId = matcherNode.placeOrder(aliceNode, wctUsdPair, BUY, wctUsdPrice, wctUsdBuyAmount).message.id
      matcherNode.waitOrderStatus(wctUsdPair, aliceOrderId, "Filled", 1.minute)

      val exchangeTx = matcherNode.transactionsByOrder(aliceOrderId).headOption.getOrElse(fail("Expected an exchange transaction"))
      nodes.waitForHeightAriseAndTxPresent(exchangeTx.id)

      val executedAmount         = correctAmount(wctUsdBuyAmount, wctUsdPrice) // 142
      val bobReceiveUsdAmount    = receiveAmount(SELL, wctUsdBuyAmount, wctUsdPrice)
      val expectedReservedBobWct = wctUsdSellAmount - executedAmount // 205 = 347 - 142

      matcherNode.reservedBalance(bobNode)(s"$WctId") shouldBe expectedReservedBobWct
      // 999999999652 = 999999999999 - 142 - 205
      matcherNode.tradableBalance(bobNode, wctUsdPair)(s"$WctId") shouldBe bobWctInitBalance - executedAmount - expectedReservedBobWct
      matcherNode.tradableBalance(bobNode, wctUsdPair)(s"$UsdId") shouldBe bobUsdBalance + bobReceiveUsdAmount

      matcherNode.reservedBalance(aliceNode) shouldBe empty
      matcherNode.tradableBalance(aliceNode, wctUsdPair)(s"$UsdId") shouldBe aliceUsdBalance - bobReceiveUsdAmount

      val expectedReservedWaves = matcherFee - LimitOrder.getPartialFee(matcherFee, wctUsdSellAmount, executedAmount)
      matcherNode.reservedBalance(bobNode)("WAVES") shouldBe expectedReservedWaves

      matcherNode.cancelOrder(bobNode, wctUsdPair, Some(matcherNode.fullOrderHistory(bobNode).head.id))
    }

    "reserved balance is empty after the total execution" in {
      val aliceOrderId = matcherNode.placeOrder(aliceNode, wctUsdPair, BUY, 100000, 5000000).message.id
      matcherNode.waitOrderStatus(wctUsdPair, aliceOrderId, "Accepted", 1.minute)

      val bobOrderId = matcherNode.placeOrder(bobNode, wctUsdPair, SELL, 99908, 5000000).message.id
      matcherNode.waitOrderStatus(wctUsdPair, bobOrderId, "Filled", 1.minute)
      matcherNode.waitOrderStatus(wctUsdPair, aliceOrderId, "Filled", 1.minute)

      val exchangeTx = matcherNode.transactionsByOrder(bobOrderId).headOption.getOrElse(fail("Expected an exchange transaction"))
      nodes.waitForHeightAriseAndTxPresent(exchangeTx.id)

      matcherNode.reservedBalance(aliceNode) shouldBe empty
      matcherNode.reservedBalance(bobNode) shouldBe empty
    }

  }

  "get opened trading markets. Check WCT-USD" in {
    val openMarkets = matcherNode.tradingMarkets()
    val markets     = openMarkets.markets.last

    markets.amountAssetName shouldBe wctAssetName
    markets.amountAssetInfo shouldBe Some(AssetDecimalsInfo(Decimals))

    markets.priceAssetName shouldBe usdAssetName
    markets.priceAssetInfo shouldBe Some(AssetDecimalsInfo(Decimals))
  }

  "Alice and Bob trade WCT-WAVES on not enough fee when place order" - {
    val wctWavesSellAmount = 2
    val wctWavesPrice      = 11234560000000L

    "bob lease all waves exact half matcher fee" in {
      val leasingAmount = bobNode.accountBalances(bobNode.address)._1 - leasingFee - matcherFee / 2
      val leaseTxId     = bobNode.lease(bobNode.address, matcherNode.address, leasingAmount, leasingFee).id
      nodes.waitForHeightAriseAndTxPresent(leaseTxId)
      val bobOrderId = matcherNode.placeOrder(bobNode, wctWavesPair, SELL, wctWavesPrice, wctWavesSellAmount).message.id
      matcherNode.waitOrderStatus(wctWavesPair, bobOrderId, "Accepted", 1.minute)

      matcherNode.tradableBalance(bobNode, wctWavesPair)("WAVES") shouldBe matcherFee / 2 + receiveAmount(SELL, wctWavesPrice, wctWavesSellAmount) - matcherFee
      matcherNode.cancelOrder(bobNode, wctWavesPair, Some(bobOrderId))

      assertBadRequestAndResponse(matcherNode.placeOrder(bobNode, wctWavesPair, SELL, wctWavesPrice, wctWavesSellAmount / 2),
                                  "Not enough tradable balance")

      val cancelLeaseTxId = bobNode.cancelLease(bobNode.address, leaseTxId, leasingFee).id
      nodes.waitForHeightAriseAndTxPresent(cancelLeaseTxId)
    }
  }

  "Alice and Bob trade ETH-WAVES" - {
    "reserved balance is empty after the total execution" in {
      val counterId1 = matcherNode.placeOrder(aliceNode, ethWavesPair, SELL, 300000, 2864310).message.id
      matcherNode.waitOrderStatus(ethWavesPair, counterId1, "Accepted", 1.minute)

      val counterId2 = matcherNode.placeOrder(aliceNode, ethWavesPair, SELL, 300000, 7237977).message.id
      matcherNode.waitOrderStatus(ethWavesPair, counterId2, "Accepted", 1.minute)

      val submittedId = matcherNode.placeOrder(bobNode, ethWavesPair, BUY, 300000, 4373667).message.id

      matcherNode.waitOrderStatus(ethWavesPair, counterId1, "Filled", 1.minute)
      matcherNode.waitOrderStatus(ethWavesPair, counterId2, "PartiallyFilled", 1.minute)
      matcherNode.waitOrderStatus(ethWavesPair, submittedId, "Filled", 1.minute)

      val exchangeTx = matcherNode.transactionsByOrder(submittedId).headOption.getOrElse(fail("Expected an exchange transaction"))
      nodes.waitForHeightAriseAndTxPresent(exchangeTx.id)

      matcherNode.reservedBalance(bobNode) shouldBe empty
      matcherNode.cancelOrder(aliceNode, ethWavesPair, Some(counterId2))
    }
  }

  "Submitted order Canceled during match" in {
    val bobOrder   = matcherNode.prepareOrder(matcherNode, wavesUsdPair, OrderType.SELL, 10L, 10000000L)
    val bobOrderId = matcherNode.placeOrder(bobOrder).message.id
    matcherNode.waitOrderStatus(wavesUsdPair, bobOrderId, "Accepted", 1.minute)

    val aliceOrder   = matcherNode.prepareOrder(aliceNode, wavesUsdPair, OrderType.BUY, 1000L, 100000L)
    val aliceOrderId = matcherNode.placeOrder(aliceOrder).message.id

    matcherNode.waitOrderStatusAndAmount(wavesUsdPair, aliceOrderId, "Filled", Some(0), 1.minute)

    withClue("Alice's reserved balance:") {
      matcherNode.reservedBalance(aliceNode) shouldBe empty
    }

    val aliceOrders = matcherNode.ordersByAddress(aliceNode, activeOnly = false, 1.minute)
    aliceOrders should not be empty

    val order = aliceOrders.find(_.id == aliceOrderId).getOrElse(throw new IllegalStateException(s"Alice should have the $aliceOrderId order"))
    order.status shouldBe "Filled"

    matcherNode.cancelOrder(matcherNode, wavesUsdPair, Some(bobOrderId))
  }

  def correctAmount(a: Long, price: Long): Long = {
    val settledTotal = (BigDecimal(price) * a / Order.PriceConstant).setScale(0, RoundingMode.FLOOR).toLong
    (BigDecimal(settledTotal) / price * Order.PriceConstant).setScale(0, RoundingMode.CEILING).toLong
  }

  def receiveAmount(ot: OrderType, matchPrice: Long, matchAmount: Long): Long =
    if (ot == BUY) correctAmount(matchAmount, matchPrice)
    else {
      (BigInt(matchAmount) * matchPrice / Order.PriceConstant).bigInteger.longValueExact()
    }

}