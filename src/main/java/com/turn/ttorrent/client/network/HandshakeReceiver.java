package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Handshake;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.ConnectionUtils;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.PeersStorageFactory;
import com.turn.ttorrent.common.TorrentsStorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.text.ParseException;
import java.util.Arrays;

public class HandshakeReceiver implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(HandshakeReceiver.class);

  private final String uid;
  private final PeersStorageFactory peersStorageFactory;
  private final TorrentsStorageFactory torrentsStorageFactory;
  private ByteBuffer messageBytes;
  private int pstrLength;

  public HandshakeReceiver(String uid, PeersStorageFactory peersStorageFactory, TorrentsStorageFactory torrentsStorageFactory) {
    this.uid = uid;
    this.peersStorageFactory = peersStorageFactory;
    this.torrentsStorageFactory = torrentsStorageFactory;
    this.pstrLength = -1;
  }

  @Override
  public DataProcessor processAndGetNext(SocketChannel socketChannel) throws IOException {
    if (pstrLength == -1) {
      ByteBuffer len = ByteBuffer.allocate(1);
      final int readBytes = socketChannel.read(len);
      if (readBytes == -1) {
        throw new IOException("Handshake size read underrrun");
      }
      if (readBytes == 0) {
        return this;
      }
      len.rewind();
      byte pstrLen = len.get();
      this.pstrLength = pstrLen;
      messageBytes = ByteBuffer.allocate(this.pstrLength + Handshake.BASE_HANDSHAKE_LENGTH);
      messageBytes.put(pstrLen);
    }
    socketChannel.read(messageBytes);
    if (messageBytes.remaining() != 0) {
      return this;
    }
    Handshake hs;
    try {
      messageBytes.rewind();
      hs = Handshake.parse(messageBytes, pstrLength);
    } catch (ParseException e) {
      logger.debug("incorrect handshake message from " + socketChannel.getLocalAddress(), e);
      return new ShutdownProcessor();
    }
    if (!torrentsStorageFactory.getTorrentsStorage().hasTorrent(hs.getHexInfoHash())) {
      logger.debug("peer {} try download torrent with hash {}, but it's unknown torrent for self",
              Arrays.toString(hs.getPeerId()),
              hs.getHexInfoHash());
      return new ShutdownProcessor();
    }

    Peer peer = peersStorageFactory.getPeersStorage().getPeer(uid);
    logger.trace("set peer id to peer " + peer);
    peer.setPeerId(ByteBuffer.wrap(hs.getPeerId()));
    peer.setTorrentHash(hs.getHexInfoHash());
    ConnectionUtils.sendHandshake(socketChannel, hs.getInfoHash(), peersStorageFactory.getPeersStorage().getSelf().getPeerIdArray());
    SharedTorrent torrent = torrentsStorageFactory.getTorrentsStorage().getTorrent(hs.getHexInfoHash());
    SharingPeer sharingPeer = new SharingPeer(peer.getIp(), peer.getPort(), peer.getPeerId(), torrent);
    sharingPeer.register(torrent);
    sharingPeer.bind(socketChannel, true);
    peersStorageFactory.getPeersStorage().addSharingPeer(peer, sharingPeer);
    return new WorkingReceiver(this.uid, peersStorageFactory, torrentsStorageFactory);
  }
}
