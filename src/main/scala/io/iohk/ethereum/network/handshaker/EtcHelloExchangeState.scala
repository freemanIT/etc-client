package io.iohk.ethereum.network.handshaker

import akka.util.ByteString
import io.iohk.ethereum.network.EtcPeerManagerActor.PeerInfo
import io.iohk.ethereum.network.PeerId
import io.iohk.ethereum.network.handshaker.Handshaker.NextMessage
import io.iohk.ethereum.network.p2p.Message
import io.iohk.ethereum.network.p2p.messages.Versions
import io.iohk.ethereum.network.p2p.messages.WireProtocol.{Capability, Disconnect, Hello}
import io.iohk.ethereum.utils.{Config, Logger, ServerStatus}


case class EtcHelloExchangeState(handshakerConfiguration: EtcHandshakerConfiguration) extends InProgressState[PeerInfo] with Logger {

  import handshakerConfiguration._

  override def nextMessage(peerId: PeerId): NextMessage = {
    log.info(s"RLPx connection established to $peerId, sending Hello")
    NextMessage(
      messageToSend = createHelloMsg(),
      timeout = peerConfiguration.waitForHelloTimeout
    )
  }

  override def applyResponseMessage(peerId: PeerId): PartialFunction[Message, HandshakerState[PeerInfo]] = {

    case hello: Hello =>
      log.info(s"Protocol handshake finished with peer $peerId ({})", hello)
      if (hello.capabilities.contains(Capability("eth", Versions.PV63.toByte)))
        EtcNodeStatusExchangeState(handshakerConfiguration)
      else {
        log.warn(s"Connected peer $peerId does not support eth {} protocol. Disconnecting.", Versions.PV63.toByte)
        DisconnectedState(Disconnect.Reasons.IncompatibleP2pProtocolVersion)
      }

  }

  override def processTimeout: HandshakerState[PeerInfo] = {
    log.warn("Timeout while waiting for Hello")
    DisconnectedState(Disconnect.Reasons.TimeoutOnReceivingAMessage)
  }

  private def createHelloMsg(): Hello = {
    val nodeStatus = nodeStatusHolder()
    val listenPort = nodeStatus.serverStatus match {
      case ServerStatus.Listening(address) => address.getPort
      case ServerStatus.NotListening => 0
    }
    Hello(
      p2pVersion = EtcHelloExchangeState.P2pVersion,
      clientId = Config.clientId,
      capabilities = Seq(Capability("eth", Versions.PV63.toByte)),
      listenPort = listenPort,
      nodeId = ByteString(nodeStatus.nodeId)
    )
  }
}

object EtcHelloExchangeState {
  val P2pVersion = 4
}
