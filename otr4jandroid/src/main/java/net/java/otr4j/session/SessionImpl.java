/*
 * otr4j, the open source java otr library.
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */

package net.java.otr4j.session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

import javax.crypto.interfaces.DHPublicKey;

import net.java.otr4j.OtrEngineHost;
import net.java.otr4j.OtrEngineListener;
import net.java.otr4j.OtrException;
import net.java.otr4j.OtrPolicy;
import net.java.otr4j.crypto.OtrCryptoEngine;
import net.java.otr4j.crypto.OtrCryptoEngineImpl;
import net.java.otr4j.crypto.OtrTlvHandler;
import net.java.otr4j.io.OtrInputStream;
import net.java.otr4j.io.OtrOutputStream;
import net.java.otr4j.io.SerializationConstants;
import net.java.otr4j.io.SerializationUtils;
import net.java.otr4j.io.messages.AbstractEncodedMessage;
import net.java.otr4j.io.messages.AbstractMessage;
import net.java.otr4j.io.messages.DataMessage;
import net.java.otr4j.io.messages.ErrorMessage;
import net.java.otr4j.io.messages.MysteriousT;
import net.java.otr4j.io.messages.PlainTextMessage;
import net.java.otr4j.io.messages.QueryMessage;
import android.util.Log;

/** @author George Politis */
public class SessionImpl implements Session {

    private static final int MIN_SESSION_START_INTERVAL = 10000;
    private SessionID sessionID;
    private OtrEngineHost host;
    private SessionStatus sessionStatus;
    private AuthContext authContext;
    private SessionKeys[][] sessionKeys;
    private Vector<byte[]> oldMacKeys;
    
    private List<OtrTlvHandler> tlvHandlers = new ArrayList<OtrTlvHandler>();
    private BigInteger ess;
    private String lastSentMessage;
    private boolean doTransmitLastMessage = false;
    private boolean isLastMessageRetransmit = false;
    private byte[] extraKey;
    private long lastStart;
    private OtrAssembler assembler;

    private final static boolean DEBUG_ENABLED = true;
    private final static String LOG_TAG = "OTR4J";

    public SessionImpl(SessionID sessionID, OtrEngineHost listener) {

        this.setSessionID(sessionID);
        this.setHost(listener);

        // client application calls OtrEngine.getSessionStatus()
        // -> create new session if it does not exist, end up here
        // -> setSessionStatus() fires statusChangedEvent
        // -> client application calls OtrEngine.getSessionStatus()
        this.sessionStatus = SessionStatus.PLAINTEXT;
	    assembler = new OtrAssembler();
    }

    @Override
    public synchronized void addTlvHandler(OtrTlvHandler handler) {
        tlvHandlers.add(handler);
    }

    @Override
    public synchronized void removeTlvHandler(OtrTlvHandler handler) {
        tlvHandlers.remove(handler);
    }

    public synchronized BigInteger getS() {
        return ess;
    }

    private SessionKeys getEncryptionSessionKeys() {
        if (DEBUG_ENABLED) Log.d(LOG_TAG,"Getting encryption keys");
        return getSessionKeysByIndex(SessionKeys.Previous, SessionKeys.Current);
    }

    private SessionKeys getMostRecentSessionKeys() {
        if (DEBUG_ENABLED) Log.d(LOG_TAG,"Getting most recent keys.");
        return getSessionKeysByIndex(SessionKeys.Current, SessionKeys.Current);
    }

    private SessionKeys getSessionKeysByID(int localKeyID, int remoteKeyID) {
        if (DEBUG_ENABLED) Log.d(LOG_TAG,"Searching for session keys with (localKeyID, remoteKeyID) = (" + localKeyID
                      + "," + remoteKeyID + ")");

        for (int i = 0; i < getSessionKeys().length; i++) {
            for (int j = 0; j < getSessionKeys()[i].length; j++) {
                SessionKeys current = getSessionKeysByIndex(i, j);
                if (current.getLocalKeyID() == localKeyID
                    && current.getRemoteKeyID() == remoteKeyID) {
                    if (DEBUG_ENABLED) Log.d(LOG_TAG,"Matching keys found.");
                    return current;
                }
            }
        }

        return null;
    }

    private SessionKeys getSessionKeysByIndex(int localKeyIndex, int remoteKeyIndex) {
        if (getSessionKeys()[localKeyIndex][remoteKeyIndex] == null)
            getSessionKeys()[localKeyIndex][remoteKeyIndex] = new SessionKeysImpl(localKeyIndex,
                    remoteKeyIndex);

        return getSessionKeys()[localKeyIndex][remoteKeyIndex];
    }

    private void rotateRemoteSessionKeys(DHPublicKey pubKey) throws OtrException {

        if (DEBUG_ENABLED) Log.d(LOG_TAG,"Rotating remote keys.");
        SessionKeys sess1 = getSessionKeysByIndex(SessionKeys.Current, SessionKeys.Previous);
        if (sess1.getIsUsedReceivingMACKey()) {
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Detected used Receiving MAC key. Adding to old MAC keys to reveal it.");
            getOldMacKeys().add(sess1.getReceivingMACKey());
        }

        SessionKeys sess2 = getSessionKeysByIndex(SessionKeys.Previous, SessionKeys.Previous);
        if (sess2.getIsUsedReceivingMACKey()) {
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Detected used Receiving MAC key. Adding to old MAC keys to reveal it.");
            getOldMacKeys().add(sess2.getReceivingMACKey());
        }

        SessionKeys sess3 = getSessionKeysByIndex(SessionKeys.Current, SessionKeys.Current);
        sess1.setRemoteDHPublicKey(sess3.getRemoteKey(), sess3.getRemoteKeyID());

        SessionKeys sess4 = getSessionKeysByIndex(SessionKeys.Previous, SessionKeys.Current);
        sess2.setRemoteDHPublicKey(sess4.getRemoteKey(), sess4.getRemoteKeyID());

        sess3.setRemoteDHPublicKey(pubKey, sess3.getRemoteKeyID() + 1);
        sess4.setRemoteDHPublicKey(pubKey, sess4.getRemoteKeyID() + 1);
    }

    private void rotateLocalSessionKeys() throws OtrException {

        if (DEBUG_ENABLED) Log.d(LOG_TAG,"Rotating local keys.");
        SessionKeys sess1 = getSessionKeysByIndex(SessionKeys.Previous, SessionKeys.Current);
        if (sess1.getIsUsedReceivingMACKey()) {
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Detected used Receiving MAC key. Adding to old MAC keys to reveal it.");
            getOldMacKeys().add(sess1.getReceivingMACKey());
        }

        SessionKeys sess2 = getSessionKeysByIndex(SessionKeys.Previous, SessionKeys.Previous);
        if (sess2.getIsUsedReceivingMACKey()) {
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Detected used Receiving MAC key. Adding to old MAC keys to reveal it.");
            getOldMacKeys().add(sess2.getReceivingMACKey());
        }

        SessionKeys sess3 = getSessionKeysByIndex(SessionKeys.Current, SessionKeys.Current);
        sess1.setLocalPair(sess3.getLocalPair(), sess3.getLocalKeyID());
        SessionKeys sess4 = getSessionKeysByIndex(SessionKeys.Current, SessionKeys.Previous);
        sess2.setLocalPair(sess4.getLocalPair(), sess4.getLocalKeyID());

        KeyPair newPair = new OtrCryptoEngineImpl().generateDHKeyPair();
        sess3.setLocalPair(newPair, sess3.getLocalKeyID() + 1);
        sess4.setLocalPair(newPair, sess4.getLocalKeyID() + 1);
    }

    private byte[] collectOldMacKeys() {
        if (DEBUG_ENABLED) Log.d(LOG_TAG,"Collecting old MAC keys to be revealed.");
        int len = 0;
        for (int i = 0; i < getOldMacKeys().size(); i++)
            len += getOldMacKeys().get(i).length;

        ByteBuffer buff = ByteBuffer.allocate(len);
        for (int i = 0; i < getOldMacKeys().size(); i++)
            buff.put(getOldMacKeys().get(i));

        getOldMacKeys().clear();
        return buff.array();
    }

    private void setSessionStatus(SessionStatus sessionStatusNew) throws OtrException {

        boolean sessionStatusChanged = (sessionStatus != sessionStatusNew);

        sessionStatus = sessionStatusNew;

        switch (sessionStatus) {
            case ENCRYPTED:
                AuthContext auth = this.getAuthContext(false);
                ess = auth.getS();
                if (DEBUG_ENABLED) Log.d(LOG_TAG,"Setting most recent session keys from auth.");
                for (int i = 0; i < this.getSessionKeys()[0].length; i++) {
                    SessionKeys current = getSessionKeysByIndex(0, i);
                    current.setLocalPair(auth.getLocalDHKeyPair(), 1);
                    current.setRemoteDHPublicKey(auth.getRemoteDHPublicKey(), 1);
                    current.setS(auth.getS());
                }

                KeyPair nextDH = new OtrCryptoEngineImpl().generateDHKeyPair();
                for (int i = 0; i < this.getSessionKeys()[1].length; i++) {
                    SessionKeys current = getSessionKeysByIndex(1, i);
                    current.setRemoteDHPublicKey(auth.getRemoteDHPublicKey(), 1);
                    current.setLocalPair(nextDH, 2);
                }

                this.setRemotePublicKey(auth.getRemoteLongTermPublicKey());

                auth.reset();

                break;
            case PLAINTEXT:
                //nothing here
                break;

            default:
                //do nothing;
        }


        if (sessionStatus == SessionStatus.ENCRYPTED && doTransmitLastMessage && lastSentMessage != null) {
            //String retransmit = (isLastMessageRetransmit ? "[resent] " : "");
            String msg = transformSending(lastSentMessage, null);
            getHost().injectMessage(getSessionID(), msg);
            sessionStatusChanged = true;
        }

        doTransmitLastMessage = false;
        isLastMessageRetransmit = false;
        lastSentMessage = null;

        if (sessionStatusChanged) {

            for (OtrEngineListener l : this.listeners)
                l.sessionStatusChanged(getSessionID());
        }


    }

    /*
     * (non-Javadoc)
     *
     * @see net.java.otr4j.session.ISession#getSessionStatus()
     */

    public SessionStatus getSessionStatus() {
        return sessionStatus;
    }

    public void setSessionID(SessionID sessionID) {
        this.sessionID = sessionID;
    }

    /*
     * (non-Javadoc)
     *
     * @see net.java.otr4j.session.ISession#getSessionID()
     */
    public SessionID getSessionID() {
        return sessionID;
    }

    private void setHost(OtrEngineHost host) {
        this.host = host;
    }

    private OtrEngineHost getHost() {
        return host;
    }

    private synchronized SessionKeys[][] getSessionKeys() {
        if (sessionKeys == null)
            sessionKeys = new SessionKeys[2][2];
        return sessionKeys;
    }

    private synchronized AuthContext getAuthContext(boolean refresh) {

//        if (authContext == null || refresh)
        if (authContext == null)
            authContext = new AuthContextImpl(this);

        return authContext;
    }

    private synchronized Vector<byte[]> getOldMacKeys() {
        if (oldMacKeys == null)
            oldMacKeys = new Vector<byte[]>();
        return oldMacKeys;
    }

    public String transformReceiving(String msgText) throws OtrException {
        return transformReceiving(msgText, null);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * net.java.otr4j.session.ISession#handleReceivingMessage(java.lang.String)
     */
    public String transformReceiving(String msgText, List<TLV> tlvs) throws OtrException, NullPointerException {

        OtrPolicy policy = getSessionPolicy();
        if (!policy.getAllowV1() && !policy.getAllowV2()) {
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Policy does not allow neither V1 not V2, ignoring message.");
            return msgText;
        }

        try {
            msgText = assembler.accumulate(msgText);
        } catch (ProtocolException e) {
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"An invalid message fragment was discarded.");
            return null;
        }

        if (msgText == null)
            return null; // Not a complete message (yet).

        AbstractMessage m;
        try {
            m = SerializationUtils.toMessage(msgText);
        } catch (IOException e) {
            throw new OtrException(e);
        }

        if (m == null)
            return null;//msgText; // Propably null or empty.

        switch (m.messageType) {
        case AbstractEncodedMessage.MESSAGE_DATA:
            return handleDataMessage((DataMessage) m, tlvs);
        case AbstractMessage.MESSAGE_ERROR:
            handleErrorMessage((ErrorMessage) m);
            return null;
        case AbstractMessage.MESSAGE_PLAINTEXT:
            return handlePlainTextMessage((PlainTextMessage) m);
        case AbstractMessage.MESSAGE_QUERY:
            handleQueryMessage((QueryMessage) m);
            return null;
        case AbstractEncodedMessage.MESSAGE_DH_COMMIT:
        case AbstractEncodedMessage.MESSAGE_DHKEY:
        case AbstractEncodedMessage.MESSAGE_REVEALSIG:
        case AbstractEncodedMessage.MESSAGE_SIGNATURE:
            AuthContext auth = this.getAuthContext(false);
            auth.handleReceivingMessage(m);

            if (auth.getIsSecure()) {
                this.setSessionStatus(SessionStatus.ENCRYPTED);
                if (DEBUG_ENABLED) Log.d(LOG_TAG,"Gone Secure.");
            }
            return null;
        default:
            throw new UnsupportedOperationException("Received an unknown message type.");
        }
    }

    private void handleQueryMessage(QueryMessage queryMessage) throws OtrException {
        if (DEBUG_ENABLED) Log.d(LOG_TAG,getSessionID().getLocalUserId() + " received a query message from "
                      + getSessionID().getRemoteUserId() + " throught "
                      + getSessionID().getProtocolName() + ".");

        setSessionStatus(SessionStatus.PLAINTEXT);

        OtrPolicy policy = getSessionPolicy();
        if (queryMessage.versions.contains(2) && policy.getAllowV2()) {
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Query message with V2 support found.");
            getAuthContext(true).respondV2Auth();
        } else if (queryMessage.versions.contains(1) && policy.getAllowV1()) {
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Query message with V1 support found - ignoring.");
        }
    }

    private void handleErrorMessage(ErrorMessage errorMessage) throws OtrException {
        if (DEBUG_ENABLED) Log.d(LOG_TAG,getSessionID().getLocalUserId() + " received an error message from "
                      + getSessionID().getRemoteUserId() + " throught " + getSessionID().getRemoteUserId()
                      + ".");

        OtrPolicy policy = getSessionPolicy();
        // Re-negotiate if we got an error and we are encrypted
        if (policy.getErrorStartAKE() && getSessionStatus() == SessionStatus.ENCRYPTED) {
            showWarning(errorMessage.error + " Initiating encryption.");

            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Error message starts AKE.");
            doTransmitLastMessage = true;
            isLastMessageRetransmit = true;

            Vector<Integer> versions = new Vector<Integer>();
            if (policy.getAllowV1())
                versions.add(1);

            if (policy.getAllowV2())
                versions.add(2);

            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Sending Query");
            injectMessage(new QueryMessage(versions));
        } else {
            showError(errorMessage.error);
        }
    }

    private synchronized String handleDataMessage(DataMessage data, List<TLV> tlvs) throws OtrException {
        if (DEBUG_ENABLED) Log.d(LOG_TAG,getSessionID().getLocalUserId() + " received a data message from "
                      + getSessionID().getRemoteUserId() + ".");

        switch (this.getSessionStatus()) {
        case ENCRYPTED:
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Message state is ENCRYPTED. Trying to decrypt message.");

            // Find matching session keys.
            int senderKeyID = data.senderKeyID;
            int receipientKeyID = data.recipientKeyID;
            SessionKeys matchingKeys = this.getSessionKeysByID(receipientKeyID, senderKeyID);

            if (matchingKeys == null) {
                throw new OtrException("no matching keys found");
            }

            // Verify received MAC with a locally calculated MAC.
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Transforming T to byte[] to calculate it's HmacSHA1.");

            byte[] serializedT;
            try {
                serializedT = SerializationUtils.toByteArray(data.getT());
            } catch (IOException e) {
                throw new OtrException(e);
            }

            OtrCryptoEngine otrCryptoEngine = new OtrCryptoEngineImpl();

            byte[] computedMAC = otrCryptoEngine.sha1Hmac(serializedT,
                    matchingKeys.getReceivingMACKey(), SerializationConstants.TYPE_LEN_MAC);

            String decryptedMsgContent = null;

            if (!Arrays.equals(computedMAC, data.mac)) {
                //    throw new OtrException("MAC verification failed, ignoring message");
                if (DEBUG_ENABLED) Log.d(LOG_TAG,"MAC verification failed, ignoring message");
            }
            else
            {


                if (DEBUG_ENABLED) Log.d(LOG_TAG, "Computed HmacSHA1 value matches sent one.");

                // Mark this MAC key as old to be revealed.
                matchingKeys.setIsUsedReceivingMACKey(true);

                matchingKeys.setReceivingCtr(data.ctr);

                byte[] dmc = otrCryptoEngine.aesDecrypt(matchingKeys.getReceivingAESKey(),
                        matchingKeys.getReceivingCtr(), data.encryptedMessage);
                try {
                    // Expect bytes to be text encoded in UTF-8.
                    decryptedMsgContent = new String(dmc, "UTF-8");

                    if (DEBUG_ENABLED)
                        Log.d(LOG_TAG, "Decrypted message: \"" + decryptedMsgContent + "\"");
                    // FIXME extraKey = authContext.getExtraSymmetricKey();


                    // Handle TLVs
                    if (tlvs == null) {
                        tlvs = new ArrayList<TLV>();
                    }

                    int tlvIndex = decryptedMsgContent.indexOf((char) 0x0);
                    if (tlvIndex > -1) {
                        decryptedMsgContent = decryptedMsgContent.substring(0, tlvIndex);
                        tlvIndex++;
                        byte[] tlvsb = new byte[dmc.length - tlvIndex];
                        System.arraycopy(dmc, tlvIndex, tlvsb, 0, tlvsb.length);

                        ByteArrayInputStream tin = new ByteArrayInputStream(tlvsb);
                        while (tin.available() > 0) {
                            int type;
                            byte[] tdata;
                            OtrInputStream eois = new OtrInputStream(tin);
                            try {
                                type = eois.readShort();
                                tdata = eois.readTlvData();
                                eois.close();
                            } catch (IOException e) {
                                throw new OtrException(e);
                            }

                            tlvs.add(new TLV(type, tdata));
                        }
                    }
                    if (tlvs.size() > 0) {
                        for (TLV tlv : tlvs) {
                            switch (tlv.getType()) {
                                case TLV.DISCONNECTED:
                                    this.setSessionStatus(SessionStatus.FINISHED);
                                    return null;
                                default:
                                    for (OtrTlvHandler handler : tlvHandlers) {
                                        handler.processTlv(tlv);
                                    }
                            }
                        }
                    }
                } catch (UnsupportedEncodingException e) {
                    //throw new OtrException(e);
                    if (DEBUG_ENABLED)
                        Log.e(LOG_TAG, "unsupported encoding exception",e);
                }
            }

            // Rotate keys if necessary.
            SessionKeys mostRecent = this.getMostRecentSessionKeys();
            if (mostRecent.getLocalKeyID() == receipientKeyID)
                this.rotateLocalSessionKeys();

            if (mostRecent.getRemoteKeyID() == senderKeyID)
                this.rotateRemoteSessionKeys(data.nextDH);

            return decryptedMsgContent;

        case FINISHED:
        case PLAINTEXT:
            showError("Unreadable encrypted message was received.");

            injectMessage(new ErrorMessage(AbstractMessage.MESSAGE_ERROR,
                    "You sent me an unreadable encrypted message"));
            throw new OtrException("Unreadable encrypted message received");
        }

        return null;
    }

    public void injectMessage(AbstractMessage m) throws OtrException {
        String msg;
        try {
            msg = SerializationUtils.toString(m);
        } catch (IOException e) {
            throw new OtrException(e);
        }
        getHost().injectMessage(getSessionID(), msg);
    }

    private String handlePlainTextMessage(PlainTextMessage plainTextMessage) throws OtrException {
      //  if (DEBUG_ENABLED) Log.d(LOG_TAG,getSessionID().getLocalUserId() + " received a plaintext message from "
        //              + getSessionID().getRemoteUserId() + " throught "
          //            + getSessionID().getProtocolName() + ".");

        OtrPolicy policy = getSessionPolicy();
        List<Integer> versions = plainTextMessage.versions;
        if (versions == null || versions.size() < 1) {
            //if (DEBUG_ENABLED) Log.d(LOG_TAG,"Received plaintext message without the whitespace tag.");
            switch (this.getSessionStatus()) {
            case ENCRYPTED:
            case FINISHED:
                // Display the message to the user, but warn him that the
                // message was received unencrypted.
                //showError("The message was received unencrypted.");
                //return "[WARNING UNENCRYPTED: " + plainTextMessage.cleanText + "]";
                return plainTextMessage.cleanText;
            case PLAINTEXT:
                // Simply display the message to the user. If
                // REQUIRE_ENCRYPTION
                // is set, warn him that the message was received
                // unencrypted.
                if (policy.getRequireEncryption()) {
                    showError("The message was received unencrypted.");
                }
                return plainTextMessage.cleanText;
            }
        } else {
          //  if (DEBUG_ENABLED) Log.d(LOG_TAG,"Received plaintext message with the whitespace tag.");
            switch (this.getSessionStatus()) {
            case ENCRYPTED:
            case FINISHED:
                // Remove the whitespace tag and display the message to the
                // user, but warn him that the message was received
                // unencrypted.
                showError("The message was received unencrypted.");
            case PLAINTEXT:
                // Remove the whitespace tag and display the message to the
                // user. If REQUIRE_ENCRYPTION is set, warn him that the
                // message
                // was received unencrypted.
                if (policy.getRequireEncryption())
                    showError("The message was received unencrypted.");
            }

            if (policy.getWhitespaceStartAKE()) {
                if (DEBUG_ENABLED) Log.d(LOG_TAG,"WHITESPACE_START_AKE is set");

                if (plainTextMessage.versions.contains(2) && policy.getAllowV2()) {
                    if (DEBUG_ENABLED) Log.d(LOG_TAG,"V2 tag found.");
                    getAuthContext(true).respondV2Auth();
                } else if (plainTextMessage.versions.contains(1) && policy.getAllowV1()) {
                    throw new UnsupportedOperationException();
                }
            }
        }

        return plainTextMessage.cleanText;
    }

    // Retransmit last sent message. Spec document does not mention where or
    // when that should happen, must check libotr code.

    public String transformSending(String msgText, List<TLV> tlvs) throws OtrException {

        switch (this.getSessionStatus()) {
        case PLAINTEXT:
            if (getSessionPolicy().getRequireEncryption()) {
                lastSentMessage = msgText;
                doTransmitLastMessage = true;
                this.startSession();
                return null;
            } else
                // TODO this does not precisly behave according to
                // specification.
                return msgText;
        case ENCRYPTED:
            this.lastSentMessage = msgText;
            if (DEBUG_ENABLED) Log.d(LOG_TAG,getSessionID().getLocalUserId() + " sends an encrypted message to "
                          + getSessionID().getRemoteUserId() + " through "
                          + getSessionID().getProtocolName() + ".");

            // Get encryption keys.
            SessionKeys encryptionKeys = this.getEncryptionSessionKeys();
            int senderKeyID = encryptionKeys.getLocalKeyID();
            int receipientKeyID = encryptionKeys.getRemoteKeyID();

            // Increment CTR.
            encryptionKeys.incrementSendingCtr();
            byte[] ctr = encryptionKeys.getSendingCtr();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (msgText != null && msgText.length() > 0)
                try {
                    out.write(msgText.getBytes("UTF8"));
                } catch (IOException e) {
                    throw new OtrException(e);
                }

            // Append tlvs
            if (tlvs != null && tlvs.size() > 0) {
                out.write((byte) 0x00);

                OtrOutputStream eoos = new OtrOutputStream(out);
                for (TLV tlv : tlvs) {
                    try {
                        eoos.writeShort(tlv.type);
                        eoos.writeTlvData(tlv.value);
                        eoos.close();
                    } catch (IOException e) {
                        throw new OtrException(e);
                    }
                }
            }

            OtrCryptoEngine otrCryptoEngine = new OtrCryptoEngineImpl();

            byte[] data = out.toByteArray();
            // Encrypt message.
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Encrypting message with keyids (localKeyID, remoteKeyID) = ("
                          + senderKeyID + ", " + receipientKeyID + ")");
            byte[] encryptedMsg = otrCryptoEngine.aesEncrypt(encryptionKeys.getSendingAESKey(),
                    ctr, data);

            // Get most recent keys to get the next D-H public key.
            SessionKeys mostRecentKeys = this.getMostRecentSessionKeys();
            DHPublicKey nextDH = (DHPublicKey) mostRecentKeys.getLocalPair().getPublic();

            // Calculate T.
            MysteriousT t = new MysteriousT(2, 0, senderKeyID, receipientKeyID, nextDH, ctr,
                    encryptedMsg);

            // Calculate T hash.
            byte[] sendingMACKey = encryptionKeys.getSendingMACKey();

            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Transforming T to byte[] to calculate it's HmacSHA1.");
            byte[] serializedT;
            try {
                serializedT = SerializationUtils.toByteArray(t);
            } catch (IOException e) {
                throw new OtrException(e);
            }

            byte[] mac = otrCryptoEngine.sha1Hmac(serializedT, sendingMACKey,
                    SerializationConstants.TYPE_LEN_MAC);

            // Get old MAC keys to be revealed.
            byte[] oldKeys = this.collectOldMacKeys();
            DataMessage m = new DataMessage(t, mac, oldKeys);

            try {
                return SerializationUtils.toString(m);
            } catch (IOException e) {
                throw new OtrException(e);
            }
        case FINISHED:
            this.lastSentMessage = msgText;
            showError("Your message to "
                    + sessionID.getRemoteUserId()
                    + " was not sent.  Either end your private conversation, or restart it.");
            return null;
        default:
            if (DEBUG_ENABLED) Log.d(LOG_TAG,"Unknown message state, not processing.");
            return msgText;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see net.java.otr4j.session.ISession#startSession()
     */
    public void startSession() throws OtrException {
        // Throttle session starts
        long now = System.currentTimeMillis();
        if (now - lastStart < MIN_SESSION_START_INTERVAL) {
            return;
        }
        lastStart = now;

        if (this.getSessionStatus() == SessionStatus.ENCRYPTED)
            return;

        if (!getSessionPolicy().getAllowV2())
            throw new OtrException("OTRv2 is not supported by this session");

        this.getAuthContext(true).startV2Auth();
    }

    /*
     * (non-Javadoc)
     *
     * @see net.java.otr4j.session.ISession#endSession()
     */
    public void endSession() throws OtrException {
        SessionStatus status = this.getSessionStatus();
        switch (status) {
        case ENCRYPTED:
            Vector<TLV> tlvs = new Vector<TLV>();
            tlvs.add(new TLV(1, null));
            String msg = this.transformSending(null, tlvs);
            getHost().injectMessage(getSessionID(), msg);
            setSessionStatus(SessionStatus.PLAINTEXT);
            break;
        case FINISHED:
            this.setSessionStatus(SessionStatus.PLAINTEXT);
            break;
        case PLAINTEXT:
            return;
        }

    }

    /*
     * (non-Javadoc)
     *
     * @see net.java.otr4j.session.ISession#refreshSession()
     */
    public void refreshSession() throws OtrException {

        long now = System.currentTimeMillis();
        if (now - lastStart < MIN_SESSION_START_INTERVAL)
            return;

        this.endSession();
        this.startSession();
    }

    private PublicKey remotePublicKey;

    private void setRemotePublicKey(PublicKey pubKey) {
        this.remotePublicKey = pubKey;
    }

    public PublicKey getRemotePublicKey() {
        return remotePublicKey;
    }

    private List<OtrEngineListener> listeners = new Vector<OtrEngineListener>();

    public void addOtrEngineListener(OtrEngineListener l) {
        synchronized (listeners) {
            if (!listeners.contains(l))
                listeners.add(l);
        }
    }

    public void removeOtrEngineListener(OtrEngineListener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    public OtrPolicy getSessionPolicy() {
        return getHost().getSessionPolicy(getSessionID());
    }

    public KeyPair getLocalKeyPair() {
        return getHost().getKeyPair(this.getSessionID());
    }

    public void showError(String warning) {
        getHost().showError(sessionID, warning);
    }

    public void showWarning(String warning) {
        getHost().showWarning(sessionID, warning);
    }

    public byte[] getExtraKey() {
        return extraKey;
    }
}
