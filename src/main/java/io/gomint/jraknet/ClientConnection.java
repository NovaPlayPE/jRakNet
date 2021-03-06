package io.gomint.jraknet;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static io.gomint.jraknet.RakNetConstraints.*;

/**
 * @author BlackyPaw
 * @version 1.0
 */
class ClientConnection extends Connection {

	// References
	private final ClientSocket client;

	// Pre-Connection attempts:
	private int connectionAttempts;
	private long lastConnectionAttempt;

	public ClientConnection( ClientSocket client, InetSocketAddress address, ConnectionState initialState ) {
		super( address, initialState );
		this.client = client;
		this.connectionAttempts = 0;
		this.lastConnectionAttempt = 0L;
	}

	// ================================ CONNECTION ================================ //

	@Override
	protected void sendRaw( InetSocketAddress recipient, PacketBuffer buffer ) throws IOException {
		this.client.send( recipient, buffer );
	}

	@Override
	protected Logger getImplementationLogger() {
		return this.client.getImplementationLogger();
	}

	@Override
	protected void preUpdate( long time ) {
		super.preUpdate( time );

		if ( this.connectionAttempts > 10 ) {
			// Nothing to update anymore
			return;
		}

		if ( this.connectionAttempts == 10 ) {
			this.propagateConnectionAttemptFailed( "Could not initialize connection" );
			++this.connectionAttempts;
			return;
		}

		// Send out pre-connection attempts:
		if ( this.lastConnectionAttempt + 1000L < time ) {
			int mtuSize = ( this.connectionAttempts < 5 ? MAXIMUM_MTU_SIZE : ( this.connectionAttempts < 8 ? 1200 : 576 ) );
			this.sendPreConnectionRequest1( this.getAddress(), mtuSize );

			++this.connectionAttempts;
			this.lastConnectionAttempt = time;
		}
	}

	@Override
	protected boolean handleDatagram0( InetSocketAddress sender, PacketBuffer datagram, long time ) {
		this.lastPingTime = time;

		// Handle special internal packets:
		byte packetId = datagram.getBuffer()[0];
		switch ( packetId ) {
			case OPEN_CONNECTION_REPLY_1:
				this.handlePreConnectionReply1( sender, datagram );
				return true;
			case OPEN_CONNECTION_REPLY_2:
				this.handlePreConnectionReply2( sender, datagram );
				return true;
			case ALREADY_CONNECTED:
				this.handleAlreadyConnected( sender, datagram );
				return true;
			case NO_FREE_INCOMING_CONNECTIONS:
				this.handleNoFreeIncomingConnections( sender, datagram );
				return true;
			case CONNECTION_REQUEST_FAILED:
				this.handleConnectionRequestFailed( sender, datagram );
				return true;
		}
		return false;
	}

	@Override
	protected boolean handlePacket0( EncapsulatedPacket packet ) {
		// Handle special internal packets:
		byte packetId = packet.getPacketData()[0];
		switch ( packetId ) {
			case CONNECTION_REQUEST_ACCEPTED:
				this.handleConnectionRequestAccepted( packet );
				return true;
		}
		return false;
	}

	@Override
	protected void propagateConnectionClosed() {
		this.client.propagateConnectionClosed( this );
	}

	@Override
	protected void propagateConnectionDisconnected() {
		this.client.propagateConnectionDisconnected( this );
	}

	@Override
	protected void propagateFullyConnected() {
		this.client.propagateConnectionRequestSucceded( this );
	}

	private void propagateConnectionAttemptFailed( String reason ) {
		this.client.propagateConnectionAttemptFailed( reason );
	}

	// ================================ PACKET HANDLERS ================================ //

	private void handlePreConnectionReply1( InetSocketAddress sender, PacketBuffer datagram ) {
		// Prevent further connection attempts:
		this.connectionAttempts = 11;

		datagram.skip( 1 );                                       // Packet ID
		datagram.readOfflineMessageDataId();                      // Offline Message Data ID
		this.setGuid( datagram.readLong() );                      // Server GUID
		boolean securityEnabled = datagram.readBoolean();         // Security Enabled
		this.setMtuSize( datagram.readUShort() );                 // MTU Size

		if ( securityEnabled ) {
			// We don't support security:
			this.setState( ConnectionState.UNCONNECTED );
			this.propagateConnectionAttemptFailed( "Security is not supported" );
			return;
		}

		this.sendPreConnectionRequest2( sender );
	}

	private void handlePreConnectionReply2( InetSocketAddress sender, PacketBuffer datagram ) {
		if ( this.getState() != ConnectionState.INITIALIZING ) {
			return;
		}

		datagram.skip( 1 );                                                                       // Packet ID
		datagram.readOfflineMessageDataId();                                                      // Offline Message Data ID
		if ( this.getGuid() != datagram.readLong() ) {                                            // Server GUID
			this.setState( ConnectionState.UNCONNECTED );
			this.propagateConnectionAttemptFailed( "Server send different GUIDs during pre-connect" );
			return;
		}

		this.setMtuSize( datagram.readUShort() );                                                 // MTU Size
		@SuppressWarnings( "unused" ) boolean securityEnabled = datagram.readBoolean();           // Security Enabled

		/* if ( securityEnabled ) {
			// We don't support security:
			this.state = ConnectionState.UNCONNECTED;
			if ( this.eventHandler != null ) {
				SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_ATTEMPT_FAILED, "Security is not supported" );
				this.eventHandler.onSocketEvent( this, event );
			}
			return;
		} */

		this.initializeStructures();
		this.setState( ConnectionState.RELIABLE );

		this.sendConnectionRequest( sender );
	}

	@SuppressWarnings( "unused" )
	private void handleAlreadyConnected( InetSocketAddress sender, PacketBuffer datagram ) {
		this.setState( ConnectionState.UNCONNECTED );
		this.propagateConnectionAttemptFailed( "System is already connected" );
	}

	@SuppressWarnings( "unused" )
	private void handleNoFreeIncomingConnections( InetSocketAddress sender, PacketBuffer datagram ) {
		this.setState( ConnectionState.UNCONNECTED );
		this.propagateConnectionAttemptFailed( "Remote peer has no free incoming connections left" );
	}

	@SuppressWarnings( "unused" )
	private void handleConnectionRequestFailed( InetSocketAddress sender, PacketBuffer datagram ) {
		this.setState( ConnectionState.UNCONNECTED );
		this.propagateConnectionAttemptFailed( "Remote peer rejected connection request" );
	}

	private void handleConnectionRequestAccepted( EncapsulatedPacket packet ) {
		PacketBuffer buffer = new PacketBuffer( packet.getPacketData(), 0 );
		buffer.skip( 1 );                                                                       // Packet ID
		buffer.readAddress();                                                                   // Client Address
		buffer.readUShort();                                                               		// Remote System Index (not always applicable)

		for ( int i = 0; i < MAX_LOCAL_IPS; ++i ) {
			buffer.readAddress();                                                               // Server Local IPs
		}

		@SuppressWarnings( "unused" ) long pingTime = buffer.readLong();                        // Ping Time
		long pongTime = buffer.readLong();                                                      // Pong Time

		// Send response:
		this.sendNewIncomingConnection( pongTime );

		// Finally we are connected!
		this.setState( ConnectionState.CONNECTED );
	}

	// ================================ PACKET SENDERS ================================ //

	private void sendPreConnectionRequest1( InetSocketAddress recipient, int mtuSize ) {
		this.setState( ConnectionState.INITIALIZING );

		PacketBuffer buffer = new PacketBuffer( MAXIMUM_MTU_SIZE );
		buffer.writeByte( OPEN_CONNECTION_REQUEST_1 );
		buffer.writeOfflineMessageDataId();
		buffer.writeByte( client.mojangModificationEnabled ? RAKNET_PROTOCOL_VERSION_MOJANG : RAKNET_PROTOCOL_VERSION );

		// Simulate filling with zeroes, in order to "test out" maximum MTU size:
		buffer.skip( mtuSize - 18 );

		try {
			this.sendRaw( recipient, buffer );
		} catch ( IOException e ) {
			// ._.
		}
	}

	private void sendPreConnectionRequest2( InetSocketAddress recipient ) {
		PacketBuffer buffer = new PacketBuffer( 34 );
		buffer.writeByte( OPEN_CONNECTION_REQUEST_2 );          // Packet ID
		buffer.writeOfflineMessageDataId();                     // Offline Message Data ID
		buffer.writeAddress( recipient );                       // Client Bind Address
		buffer.writeUShort( this.getMtuSize() );                // MTU size
		buffer.writeLong( this.client.getGuid() );              // Client GUID

		try {
			this.sendRaw( recipient, buffer );
		} catch ( IOException e ) {
			// ._.
		}
	}

	private void sendConnectionRequest( @SuppressWarnings( "unused" ) InetSocketAddress recipient ) {
		PacketBuffer buffer = new PacketBuffer( 18 );
		buffer.writeByte( CONNECTION_REQUEST );                 // Packet ID
		buffer.writeLong( this.client.getGuid() );              // Client GUID
		buffer.writeLong( System.currentTimeMillis() );         // Ping Time
		buffer.writeBoolean( false );                           // Security Enabled

		/*                  PASSWORD HANDLING
		String password = ...;
		buffer.writeBytes( password.getBytes( StandardCharsets.US_ASCII ) );
		*/

		this.send( PacketReliability.RELIABLE_ORDERED, 0, buffer.getBuffer(), buffer.getBufferOffset(), buffer.getPosition() - buffer.getBufferOffset() );
	}

	private void sendNewIncomingConnection( long pingTime ) {
		PacketBuffer buffer = new PacketBuffer( 94 );
		buffer.writeByte( NEW_INCOMING_CONNECTION );
		buffer.writeAddress( this.getAddress() );
		for ( int i = 0; i < MAX_LOCAL_IPS; ++i ) {
			buffer.writeAddress( ServerConnection.LOCAL_IP_ADDRESSES[i] );
		}
		buffer.writeLong( pingTime );
		buffer.writeLong( System.currentTimeMillis() );

		this.send( PacketReliability.RELIABLE_ORDERED, 0, buffer.getBuffer() );
	}

}