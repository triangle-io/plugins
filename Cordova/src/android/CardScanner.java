package io.triangle.cordova;

import android.os.Parcel;
import android.content.Intent;
import android.util.Log;
import android.util.Base64;
import io.triangle.Session;
import io.triangle.TriangleException;
import io.triangle.reader.PaymentCard;
import io.triangle.reader.TapListener;
import io.triangle.reader.TapProcessor;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.IllegalStateException;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.BadPaddingException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * Exposes methods that enable extraction of credit card details through the Triangle APIs.
 */
public class CardScanner extends CordovaPlugin implements TapListener
{
    /**
     * Main class handling the tapping of credit card to the device.
     */
    private TapProcessor tapProcessor;

    /**
     * Identifier of JavaScript events.
     */
    private static String EVENT_TAP_ERROR = "ontaperror";
    private static String EVENT_TAP_DETECT = "ontapdetect";
    private static String EVENT_TAP_SUCCESS = "ontapsuccess";

    private static String LOG_TAG = "TrianglePlugin";

    /**
     * Variable related to decryption of data
     */
    private static boolean IS_DECRYPTING = false;
    private static String MODULUS;
    private static String D;
    private static final String RSAKeyFactory = "RSA";
    private static final String RSAKeyAlgorithm = "RSA/ECB/PKCS1Padding";
    private static final String UTF_8 = "UTF-8";
    private static final int BASE_64_FLAG = Base64.NO_WRAP;
    private static PrivateKey PRIVATE_KEY;

    @Override
    public void onResume(boolean multitasking)
    {
        super.onResume(multitasking);

        // Resume acceptance of taps
        if (Session.getInstance().isInitialized() && this.tapProcessor != null)
        {
            this.tapProcessor.resume();
        }
    }

    @Override
    public void onPause(boolean multitasking)
    {
        super.onPause(multitasking);

        // Stop accepting taps
        if (Session.getInstance().isInitialized() && this.tapProcessor != null)
        {
            this.tapProcessor.pause();
        }
    }

    @Override
    public void onNewIntent(Intent intent)
    {
        if (Session.getInstance().isInitialized() && this.tapProcessor != null)
        {
            this.tapProcessor.processIntent(intent);
        }
    }

    /**
     * Initializes the underlying Triangle session.
     * @param applicationId ID of the application as defined in triangle.io
     * @param accessKey Access key of the account using the API
     * @param secretKey Secret key of the account using the API
     */
    private void initializeTriangleSession(String applicationId, String accessKey, String secretKey,
                                           final CallbackContext callbackContext)
    {
        try
        {
            Session.getInstance().initialize(applicationId, accessKey, secretKey, this.cordova.getActivity().getApplication());

            // Initialize the TapProcessor class now that the Session has been initialized
            this.tapProcessor = new TapProcessor(this.cordova.getActivity());

            // Subscribe to events raised during processing of credit card taps
            this.tapProcessor.setTapListener(this);
        }
        catch (TriangleException e)
        {
            // If there were any errors initializing the Session, let the js side know via the callback
            callbackContext.error(e.getMessage());

            return;
        }

        // Perform the rest of the operation on UI thread
        this.cordova.getActivity().runOnUiThread(new Runnable()
        {
            public void run()
            {
                // Start listening to taps right away
                CardScanner.this.tapProcessor.resume();

                // Indicate success to the .js side so that further actions can be invoked
                callbackContext.success();
            }
        });
    }

    /**
     * Raises an event on the JavaScript side.
     * @param eventName a String identifying the event name.
     * @param messageData the data associated with the message. May be null.
     */
    private void raiseJavaScriptEvent(String eventName, JSONObject messageData)
    {
        String statement = null;

        if (messageData != null)
        {
            statement = String.format("cordova.fireDocumentEvent('%s', %s);",
                    eventName,
                    messageData.toString());
        }
        else
        {
            statement = String.format("cordova.fireDocumentEvent('%s');", eventName);
        }

        // Surround statement with try/catch
        statement = "try {" + statement + "} catch (err) { console.log('error sending javascript from Android'); }";

        this.webView.sendJavascript(statement);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException
    {
        if (action.equalsIgnoreCase("initialize"))
        {
            final String applicationId = args.getString(0);
            final String accessKey = args.getString(1);
            final String secretKey = args.getString(2);

            // Capture call back context for delegate
            final CallbackContext callback = callbackContext;

            // Execute initialization on a thread other than the core WebView thread
            this.cordova.getThreadPool().execute(new Runnable()
            {
                @Override
                public void run()
                {
                    CardScanner.this.initializeTriangleSession(applicationId, accessKey, secretKey, callback);
                }
            });

            return true;
        }
        else if (action.equalsIgnoreCase("decrypt"))
        {
            final String modulus = args.getString(0);
            final String d = args.getString(1);

            try
            {
                this.startDecryption(modulus, d);
            }
            catch (Exception ex)
            {
                // Indicate error to JS side
                callbackContext.error(ex.getMessage());
            }

            // All went through just fine
            callbackContext.success();
        }
        else if (action.equalsIgnoreCase("encrypt"))
        {
            this.stopDecryption();

            // No errors can ever occur for this method
            callbackContext.success();
        }

        return super.execute(action, args, callbackContext);
    }

    /**
     * Starts returning data obtained from the underlying Triangle API in encrypted form.
     */
    private void stopDecryption()
    {
        IS_DECRYPTING = false;
        MODULUS = "";
        D = "";
        PRIVATE_KEY = null;
    }

    /**
     * Starts decrypting information received from the underlying Triangle APIs
     * @param modulus Modulus used to decrypt the data (RSA variable)
     * @param d D used to decrypt the data (RSA variable)
     */
    private void startDecryption(String modulus, String d) throws UnsupportedEncodingException,
            NoSuchAlgorithmException, InvalidKeySpecException
    {
        if (modulus == null || "".equals(modulus))
        {
            throw new IllegalArgumentException("modulus");
        }

        if (d == null || "".equals(d))
        {
            throw new IllegalArgumentException("d");
        }

        IS_DECRYPTING = true;
        MODULUS = modulus;
        D = d;

        // Construct the private key
        KeyFactory keyFactory = KeyFactory.getInstance(RSAKeyFactory);
        PRIVATE_KEY = keyFactory.generatePrivate(new RSAPrivateKeySpec(
                new BigInteger(1, Base64.decode(MODULUS, BASE_64_FLAG)),
                new BigInteger(1, Base64.decode(D, BASE_64_FLAG))));
    }

    @Override
    public void onTapDetect()
    {
        Log.d(LOG_TAG, "Tap detected.");

        this.raiseJavaScriptEvent(EVENT_TAP_DETECT, null);
    }

    @Override
    public void onTapError(Exception e)
    {
        Log.d(LOG_TAG, "Tap error detected.");

        JSONObject jsonObject = new JSONObject();

        try
        {
            jsonObject.put("error", e.getMessage());
        }
        catch (JSONException e1)
        {
            // Should never occur
        }

        this.raiseJavaScriptEvent(EVENT_TAP_ERROR, jsonObject);
    }

    @Override
    public void onTapSuccess(PaymentCard paymentCard)
    {
        Log.d(LOG_TAG, "Tap successfully processed.");

        // Dump the payment card class into a JSONObject
        JSONObject jsonObject = new JSONObject();

        try
        {
            jsonObject.put("lastFourDigits", paymentCard.getLastFourDigits());
            jsonObject.put("cardholderName", paymentCard.getCardholderName());
            jsonObject.put("cardBrand", paymentCard.getCardBrand());
            jsonObject.put("activationDate", paymentCard.getActivationDate());
            jsonObject.put("expiryDate", paymentCard.getExpiryDate());
            jsonObject.put("cardPreferredName", paymentCard.getCardPreferredName());
            jsonObject.put("encryptedAccountNumber", JSONObject.quote(paymentCard.getEncryptedAccountNumber()));

            // If we should decrypt the information, then also provide the information in decrypted format
            if (IS_DECRYPTING)
            {
                try
                {
                    jsonObject.put("plainAccountNumber", JSONObject.quote(this.decrypt(paymentCard.getEncryptedAccountNumber())));
                }
                catch (Exception ex)
                {
                    jsonObject.put("plainAccountNumber", JSONObject.quote(ex.getMessage()));
                }
            }
        }
        catch (JSONException e)
        {
            // Should never occur as we are putting string and date data only
        }

        // Finally send the message across
        this.raiseJavaScriptEvent(EVENT_TAP_SUCCESS, jsonObject);
    }

    /**
     * Decrypts information based on keys provided for decryption.
     */
    private String decrypt(String cipherText) throws UnsupportedEncodingException,
            NoSuchAlgorithmException, InvalidKeySpecException, InvalidKeyException, IllegalBlockSizeException,
            NoSuchPaddingException, BadPaddingException
    {
        if (!IS_DECRYPTING)
        {
            throw new IllegalStateException("Decryption flag must be on before decryption can occur");
        }

        byte[] cipherBytes = Base64.decode(cipherText, BASE_64_FLAG);
        Cipher cipher = Cipher.getInstance(RSAKeyAlgorithm);
        cipher.init(Cipher.DECRYPT_MODE, PRIVATE_KEY);
        byte[] plainData = cipher.doFinal(cipherBytes);
        return new String(plainData, UTF_8);
    }
}
