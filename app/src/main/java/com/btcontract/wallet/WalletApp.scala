package com.btcontract.wallet

import R.string._
import org.bitcoinj.core._
import com.btcontract.wallet.Utils._
import org.bitcoinj.core.listeners.TransactionConfidenceEventListener
import collection.JavaConverters.asScalaBufferConverter
import com.google.common.util.concurrent.Service.State
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.wallet.KeyChain.KeyPurpose
import org.bitcoinj.wallet.Wallet.BalanceType
import org.bitcoinj.crypto.KeyCrypterScrypt
import com.google.protobuf.ByteString
import org.bitcoinj.wallet.{Wallet, Protos}
import android.app.Application
import android.widget.Toast
import java.io.File

import org.bitcoinj.uri.{BitcoinURIParseException, OptionalFieldValidationException}
import org.bitcoinj.uri.{RequiredFieldValidationException, BitcoinURI}
import android.content.{ClipData, ClipboardManager, Context}
import com.btcontract.wallet.helper.{FiatRates, Fee}
import State.{STARTING, RUNNING}

import java.util.concurrent.TimeUnit.MILLISECONDS
import Context.CLIPBOARD_SERVICE


class WalletApp extends Application {
  lazy val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
  lazy val params = org.bitcoinj.params.MainNetParams.get
  var walletFile, chainFile: java.io.File = null
  var kit: WalletKit = null

  lazy val plur = getString(lang) match {
    case "eng" | "esp" => (opts: Array[String], num: Int) => if (num == 1) opts(1) else opts(2)
    case "chn" | "jpn" => (phraseOptions: Array[String], num: Int) => phraseOptions(1)
    case "rus" | "ukr" => (phraseOptions: Array[String], num: Int) =>

      val reminder100 = num % 100
      val reminder10 = reminder100 % 10
      if (reminder100 > 10 & reminder100 < 20) phraseOptions(3)
      else if (reminder10 > 1 & reminder10 < 5) phraseOptions(2)
      else if (reminder10 == 1) phraseOptions(1)
      else phraseOptions(3)
  }

  // Both these methods may throw
  def getTo(base58: String) = Address.fromBase58(params, base58)
  def getTo(out: TransactionOutput) = out.getScriptPubKey.getToAddress(params, true)
  def isAlive = if (null == kit) false else kit.state match { case STARTING | RUNNING => true case _ => false }
  def plurOrZero(opts: Array[String], number: Int) = if (number > 0) plur(opts, number) format number else opts(0)

  def setBuffer(bufferMessage: String) = {
    val mgr = getSystemService(CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
    mgr setPrimaryClip ClipData.newPlainText(Utils.appName, bufferMessage)
    Toast.makeText(this, copied_to_clipboard, Toast.LENGTH_LONG).show
  }

  // Startup actions
  override def onCreate = Utils.wrap(super.onCreate) {
    chainFile = new File(getFilesDir, s"${Utils.appName}.spvchain")
    walletFile = new File(getFilesDir, s"${Utils.appName}.wallet")
    Utils.startupAppReference = this
    FiatRates.go
    Fee.go
  }

  object TransData {
    var value = Option.empty[Any]
    def setValue(text: String) = value = Option {
      // Both getTo and BitcoinUri may throw so watch over
      if (text startsWith "groestlcoin") new BitcoinURI(params, text)
      else getTo(text)
    }

    def onFail(err: Int => Unit): PartialFunction[Throwable, Unit] = {
      case _: RequiredFieldValidationException => err(err_required_field)
      case _: OptionalFieldValidationException => err(err_optional_field)
      case _: WrongNetworkException => err(err_different_net)
      case _: AddressFormatException => err(err_address)
      case _: BitcoinURIParseException => err(err_uri)
      case _: ArithmeticException => err(err_neg)
      case _: Throwable => err(err_general)
    }
  }

  abstract class WalletKit extends AbstractKit {
    def autoSaveOn = wallet.autosaveToFile(walletFile, 500, MILLISECONDS, null)
    def freshOuts = wallet.calculateAllSpendCandidates(false, true).asScala
    def currentBalance = wallet getBalance BalanceType.ESTIMATED_SPENDABLE
    def currentAddress = wallet currentAddress KeyPurpose.RECEIVE_FUNDS
    override def shutDown = if (peerGroup.isRunning) peerGroup.stop

    def encryptWallet(pass: CharSequence) = {
      val randSalt = ByteString copyFrom KeyCrypterScrypt.randomSalt
      val scryptBuilder = Protos.ScryptParameters.newBuilder setSalt randSalt setN 65536
      val cr = new KeyCrypterScrypt(scryptBuilder.build)
      wallet.encrypt(cr, cr deriveKey pass)
    }

    def useCheckPoints(time: Long) = {
      val pts = getAssets open "checkpoints.txt"
      CheckpointManager.checkpoint(params, pts, store, time)
    }

    def setupAndStartDownload = {
      wallet addTransactionConfidenceEventListener Vibr.listener
      wallet addChangeEventListener Vibr.listener
      wallet.allowSpendingUnconfirmedTransactions
      peerGroup addPeerDiscovery new DnsDiscovery(params)
      peerGroup.setUserAgent(Utils.appName, "1.06")
      peerGroup setDownloadTxDependencies -1
      peerGroup setPingIntervalMsec 10000
      peerGroup setMaxConnections 10
      peerGroup addWallet wallet
      startDownload
      autoSaveOn
    }
  }
}

object Vibr {
  def vibrate(pattern: Pattern) = if (null != vib && vib.hasVibrator) vib.vibrate(pattern, -1)
  lazy val vib = app.getSystemService(Context.VIBRATOR_SERVICE).asInstanceOf[android.os.Vibrator]
  val confirmed = Array(0L, 75, 250, 75, 250)
  val processed = Array(0L, 85, 200)
  type Pattern = Array[Long]

  val listener = new TransactionConfidenceEventListener with MyWalletChangeListener {
    def onTransactionConfidenceChanged(w: Wallet, tx: Transaction) = if (tx.getConfidence.getDepthInBlocks == 1) vibrate(confirmed)
    def onCoinsReceived(w: Wallet, t: Transaction, pb: Coin, nb: Coin) = if (nb isGreaterThan pb) vibrate(processed)
    def onCoinsSent(w: Wallet, t: Transaction, pb: Coin, nb: Coin) = vibrate(processed)
  }
}