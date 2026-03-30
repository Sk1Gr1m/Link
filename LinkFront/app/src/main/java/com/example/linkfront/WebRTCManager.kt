package com.example.linkfront

import android.content.Context
import org.webrtc.*
import java.nio.ByteBuffer

class WebRTCManager(context: Context) {
    private val peerConnectionFactory: PeerConnectionFactory
    private val peerA: PeerConnection
    private val peerB: PeerConnection
    private var channelA: DataChannel? = null
    private var channelB: DataChannel? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        peerA = createPeer(rtcConfig, "A")!!
        peerB = createPeer(rtcConfig, "B")!!

        channelA = peerA.createDataChannel("chat", DataChannel.Init())
        channelA?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                println("Channel A state: ${channelA?.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                println("A received: ${String(data)}")
            }
        })

        startConnection()
    }

    private fun createPeer(config: PeerConnection.RTCConfiguration, label: String): PeerConnection? {
        return peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(p0: IceCandidate) {
                if (label == "A") peerB.addIceCandidate(p0)
                else peerA.addIceCandidate(p0)
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
            override fun onDataChannel(p0: DataChannel) {
                if (label == "B") {
                    channelB = p0
                    channelB?.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(p0: Long) {}
                        override fun onStateChange() {
                            println("Channel B state: ${channelB?.state()}")
                        }
                        override fun onMessage(buffer: DataChannel.Buffer) {
                            val data = ByteArray(buffer.data.remaining())
                            buffer.data.get(data)
                            println("B received: ${String(data)}")
                        }
                    })
                }
            }
            override fun onAddStream(p0: MediaStream) {}
            override fun onRemoveStream(p0: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver, p1: Array<out MediaStream>) {}
        })
    }

    private fun startConnection() {
        val constraints = MediaConstraints()

        peerA.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(offer: SessionDescription?) {
                if (offer == null) return
                peerA.setLocalDescription(SimpleSdpObserver(), offer)
                peerB.setRemoteDescription(SimpleSdpObserver(), offer)

                peerB.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) return
                        peerB.setLocalDescription(SimpleSdpObserver(), answer)
                        peerA.setRemoteDescription(SimpleSdpObserver(), answer)
                    }
                }, constraints)
            }
        }, constraints)
    }

    fun sendFromAToB(message: String) {
        val buffer = DataChannel.Buffer(
            ByteBuffer.wrap(message.toByteArray()),
            false
        )
        channelA?.send(buffer)
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
