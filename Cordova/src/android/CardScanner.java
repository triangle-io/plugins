package io.triangle.cordova;

import android.os.Parcel;
import io.triangle.Session;
import io.triangle.TriangleException;
import io.triangle.reader.PaymentCard;
import io.triangle.reader.TapListener;
import io.triangle.reader.TapProcessor;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

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
     * Messages used to indicate type of event to the js side.
     */
    private static int MESSAGE_TAP_ERROR = 1;
    private static int MESSAGE_TAP_DETECT = 2;
    private static int MESSAGE_TAP_SUCCESS = 3;

    /**
     * Callback context used to transfer data back to the js side.
     */
    private CallbackContext callbackContext;

    public CardScanner()
    {
        this.tapProcessor = new TapProcessor(this.cordova.getActivity());

        // Subscribe to events raised during processing of credit card taps
        this.tapProcessor.setTapListener(this);
    }

    @Override
    public void onResume(boolean multitasking)
    {
        super.onResume(multitasking);

        // Resume acceptance of taps
        if (Session.getInstance().isInitialized())
        {
            this.tapProcessor.resume();
        }
    }

    @Override
    public void onPause(boolean multitasking)
    {
        super.onPause(multitasking);

        // Stop accepting taps
        if (Session.getInstance().isInitialized())
        {
            this.tapProcessor.pause();
        }
    }

    /**
     * Initializes the underlying Triangle session.
     * @param applicationId ID of the application as defined in triangle.io
     * @param accessKey Access key of the account using the API
     * @param secretKey Secret key of the account using the API
     */
    private void initializeTriangleSession(String applicationId, String accessKey, String secretKey, CallbackContext callbackContext)
    {
        try
        {
            Session.getInstance().initialize(applicationId, accessKey, secretKey, this.cordova.getActivity().getApplication());
        }
        catch (TriangleException e)
        {
            // If there were any errors initializing the Session, let the js side know via the callback
            callbackContext.error(e.getMessage());

            return;
        }

        // Start listening to taps right away
        this.tapProcessor.resume();

        // Indicate success to the .js side so that further actions can be invoked
        callbackContext.success();
    }

    private void activate(CallbackContext callbackContext)
    {
        this.callbackContext = callbackContext;
    }

    private void deactivate()
    {
        this.callbackContext = null;
    }

    /**
     * Sends a message to the js side.
     * @param messageType an integer indicating the type of message being sent.
     * @param messageData the data associated with the message. May be null.
     */
    private void sendMessage(int messageType, JSONObject messageData)
    {
        if (this.callbackContext != null)
        {
            String messageContents = "{id: " +
                    String.valueOf(messageType) +
                    ",data: " +
                    (messageData == null ? "null" : messageData.toString()) +
                    "}";

            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, messageContents);

            // We like to be able to reuse the same callback multiple times
            pluginResult.setKeepCallback(true);

            // Send the data to the js side
            this.callbackContext.sendPluginResult(pluginResult);
        }
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException
    {
        boolean handled = false;

        if (action.equalsIgnoreCase("initialize"))
        {
            String applicationId = args.getString(0);
            String accessKey = args.getString(1);
            String secretKey = args.getString(2);

            this.initializeTriangleSession(applicationId, accessKey, secretKey, callbackContext);

            handled = true;
        }
        else if (action.equalsIgnoreCase("activate"))
        {
            this.activate(callbackContext);

            handled = true;
        }
        else if (action.equalsIgnoreCase("deactivate"))
        {
            this.deactivate();

            // deactivation always succeeds
            callbackContext.success();

            handled = true;
        }

        if (handled)
        {
            return true;
        }
        else
        {
            return super.execute(action, args, callbackContext);
        }
    }

    @Override
    public void onTapDetect()
    {
        this.sendMessage(MESSAGE_TAP_DETECT, null);
    }

    @Override
    public void onTapError(Exception e)
    {
        JSONObject jsonObject = new JSONObject();

        try
        {
            jsonObject.put("error", e.getMessage());
        }
        catch (JSONException e1)
        {
            // Should never occur
        }

        this.sendMessage(MESSAGE_TAP_ERROR, jsonObject);
    }

    @Override
    public void onTapSuccess(PaymentCard paymentCard)
    {
        // Dump the payment card class into a JSONObject
        Parcel paymentCardParcel = Parcel.obtain();
        paymentCard.writeToParcel(paymentCardParcel, 0);
        Map<String, Object> dataMap = new HashMap<String, Object>();
        paymentCardParcel.writeMap(dataMap);
        JSONObject jsonObject = new JSONObject(dataMap);

        // Finally send the message across
        this.sendMessage(MESSAGE_TAP_SUCCESS, jsonObject);
    }
}
