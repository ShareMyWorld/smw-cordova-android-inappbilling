/*
 * Cordova In App Billing for Android
 */
package se.sharemyworld.plugins.inappbilling;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.android.vending.billing.IInAppBillingService;
import java.util.ArrayList;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import se.sharemyworld.sharemyworld.ShareMyWorld;

/**
 * InAppBilling plugin for cordova by design made to be as thin as possible on
 * the java side. It does not support subscriptions nor client side
 * verification, use a server instead.
 *
 * @author david
 */
public class InAppBilling extends CordovaPlugin {

    private static final String LOG_TAG = "InAppBilling";
    private static final int BUY_REQUEST = 100;
    private static final int RESPONSE_OK = 0;
    private IInAppBillingService billingService;
    private ServiceConnection serviceConnection;
    private CallbackContext callbackContext;

    public void bindService(final CallbackContext callbackContext) {
        //setup service connection
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName name) {
                billingService = null;
                if (callbackContext != null && !callbackContext.isFinished()) {
                    callbackContext.error("InAppBilling service disconnected a bit suplrisingly");
                }

            }

            @Override
            public void onServiceConnected(ComponentName name,
                    IBinder service) {
                billingService = IInAppBillingService.Stub.asInterface(service);
                callbackContext.success();
            }
        };
        cordova.getActivity()
                .bindService(new Intent("com.android.vending.billing.InAppBillingService.BIND"),
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {

        if ("init".equals(action)) {
            bindService(callbackContext);
            return true;
        } else if ("isBillingSupported".equals(action)) {
            isBillingSupported(args, callbackContext);
            return true;
        } else if ("buy".equals(action)) {
            buy(args, callbackContext);
            return true;
        } else if ("getSKUDetails".equals(action)) {
            getSKUDetails(args, callbackContext);
            return true;
        } else if ("getPurchases".equals(action)) {
            getPurchases(args, callbackContext);
            return true;
        } else if ("consumePurchase".equals(action)) {
            consumePurchase(args, callbackContext);
            return true;
        }
        return false;
    }

    private void isBillingSupported(JSONArray args, CallbackContext callbackContext) {

        //do this in a seperate thread since it can result in an network request
        //which could hang a long time
        cordova.getThreadPool().execute(new PluginWorker(callbackContext, args) {
            public void doWork() throws JSONException, RemoteException {
                String type = args.getString(0);
                int code = billingService.isBillingSupported(3, cordova.getActivity().getApplicationContext().getPackageName(), type);

                if (code == RESPONSE_OK) {
                    ctx.success(0);
                } else {
                    callbackError(ctx, "isBillingSupported", code, "Billing is not supported, type: " + type);
                }
            }
        });
    }

    /**
     * Calls getSKUDetails,transforms the result into a JSONArray and sends it
     * back to javascript land. It does this in a seperate thread so web core
     * thread is not blocked.
     *
     * @param args
     * @param callbackContext
     * @throws JSONException
     */
    private void getSKUDetails(JSONArray args, CallbackContext callbackContext) {


        //do this in a seperate thread since it can result in an network request
        //which could hang a long time
        cordova.getThreadPool().execute(new PluginWorker(callbackContext, args) {
            public void doWork() throws RemoteException, JSONException {
                String type = args.getString(0);
                Bundle skuBundle = new Bundle();
                skuBundle.putStringArrayList("ITEM_ID_LIST", toList(args.getJSONArray(1)));


                JSONArray result;
                Bundle bdetails = billingService.getSkuDetails(3, cordova.getActivity().getApplicationContext().getPackageName(), type, skuBundle);
                if (bdetails.containsKey("DETAILS_LIST")) {
                    result = new JSONArray(bdetails.getStringArrayList("DETAILS_LIST"));
                    ctx.success(result);
                } else {
                    callbackError(ctx, "getSKUDetails", "Could not get SKU details");
                }

            }
        });
    }

    /**
     * Sends off a buy intent
     *
     * @param args
     * @param callbackContext
     * @throws JSONException
     */
    private void buy(JSONArray args, CallbackContext callbackContext) {
        //We don't do this in a worker thread since setting activity callback
        //and sending of intent is not done in a thread safe maner, i.e. not atomic
        try {
            //get the arguments
            String sku = args.getString(0);
            String type = args.getString(1);
            String developerPayload = args.getString(2);

            if (billingService == null) {
                Log.d(LOG_TAG, "Billing service is disconnected or hasn't been initialized");
                callbackError(callbackContext, "serviceDisconnected", "Billing service has disconnected");
            }

            //create intent and send it of 
            Bundle bundle = billingService.getBuyIntent(3, cordova.getActivity().getApplication().getPackageName(), sku, type, developerPayload);
            int response_code = bundle.getInt("RESPONSE_CODE");
            if (response_code == RESPONSE_OK) {
                //set this plugin as the plugin to issue callback to
                //since we cant use CordovaInterface.startActivityForResult().
                //It uses Activity.startActivityForResult
                //and we like to use start IntentSenderForResult below
                this.cordova.setActivityResultCallback(this);
                ShareMyWorld smw = (ShareMyWorld) cordova.getActivity();
                smw.setActivityResultKeepRunning(smw.isKeepRunning());
                smw.setKeepRunning(false);

                this.callbackContext = callbackContext;

                PendingIntent pendingIntent = bundle.getParcelable("BUY_INTENT");
                cordova.getActivity().startIntentSenderForResult(pendingIntent.getIntentSender(),
                        BUY_REQUEST, new Intent(), Integer.valueOf(0), Integer.valueOf(0),
                        Integer.valueOf(0));
            } else {
                Log.e(LOG_TAG, "Could not create buy intent, error code " + response_code);
                callbackError(callbackContext, "getBuyIntent", response_code, "Could not create buy intent");
            }

        } catch (JSONException e) {
            //in keeping with our philosophy we just shove exceptions over to the 
            //javascript side
            Log.e(LOG_TAG, "Arguments error " + e.getMessage(), e);
            callbackContext.error(jsonify(e));
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "RemoteException " + e.getMessage(), e);
            callbackContext.error(jsonify(e));
        } catch (IntentSender.SendIntentException e) {
            Log.e(LOG_TAG, "SendIntentException " + e.getMessage(), e);
            callbackContext.error(jsonify(e));
        }
    }

    /**
     * Respond to buy intents (and in the future it might be subscriptions)
     *
     * @param requestCode
     * @param resultCode
     * @param intent
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == BUY_REQUEST && this.callbackContext != null) {
            if (intent == null) {
                Log.e(LOG_TAG, "No intent data from play: resul tcode" + resultCode);
                callbackError(this.callbackContext, "onActivityResult", "No intent data from play");
                return;
            }
            try {
                int code = getResponseCodeFromIntent(intent);
                if (code == RESPONSE_OK) {
                    JSONObject result = new JSONObject();
                    result.put("code", code);
                    result.put("json", intent.getStringExtra("INAPP_PURCHASE_DATA"));
                    result.put("signature", intent.getStringExtra("INAPP_DATA_SIGNATURE"));
                    this.callbackContext.success(result);
                } else {
                    callbackError(this.callbackContext, "onActivityResult:buy", code, "Error in purchase");
                }
            } catch (JSONException ex) {
                this.callbackContext.error(jsonify(ex));
            }
        }
    }

    private void getPurchases(JSONArray args, CallbackContext callbackContext) {
        //do this in a seperate thread since it can result in an network request
        //which could hang a long time
        cordova.getThreadPool().execute(new PluginWorker(callbackContext, args) {
            public void doWork() throws JSONException, RemoteException {
                String type = args.getString(0);
                String continuationToken = null;
                if (args.length() > 1) {
                    continuationToken = args.getString(1);
                }

                Bundle bundle = billingService.getPurchases(3, cordova.getActivity().getApplication().getPackageName(), type, continuationToken);

                int responseCode = bundle.getInt("RESPONSE_CODE");
                Log.d(LOG_TAG, "getPurchases code " + responseCode);
                if (responseCode == RESPONSE_OK) {
                    //RESPONSE_OK
                    JSONObject result = new JSONObject();
                    result.put("code", responseCode);
                    result.put("items", new JSONArray(bundle.getStringArrayList("INAPP_PURCHASE_ITEM_LIST")));
                    result.put("datas", new JSONArray(bundle.getStringArrayList("INAPP_PURCHASE_DATA_LIST")));
                    result.put("signatures", new JSONArray(bundle.getStringArrayList("INAPP_DATA_SIGNATURE_LIST")));
                    result.put("token", bundle.getString("INAPP_CONTINUATION_TOKEN"));
                    ctx.success(result);
                } else {
                    callbackError(ctx, "getPurchases", responseCode, "An error has occured. Check error code.");
                }
            }
        });
    }

    private void consumePurchase(JSONArray args, CallbackContext callbackContext) {
        //do this in a seperate thread since it can result in an network request
        //which could hang a long time
        cordova.getThreadPool().execute(new PluginWorker(callbackContext, args) {
            public void doWork() throws RemoteException, JSONException {
                String purchaseToken = args.getString(0);
                int responseCode = billingService.consumePurchase(3, cordova.getActivity().getApplication().getPackageName(), purchaseToken);

                if (responseCode == 0) {
                    //RESPONSE_OK
                    ctx.success(responseCode);
                } else {
                    callbackError(ctx, "consumePurchase", responseCode, "An error has occured. Check error code.");
                }
            }
        });
    }

    @Override
    public void onDestroy() {

        super.onDestroy(); //To change body of generated methods, choose Tools | Templates.
        if (serviceConnection != null) {
            cordova.getActivity().unbindService(serviceConnection);
        }
    }

    //helpers
    private JSONObject jsonify(Exception e) {
        JSONObject error = new JSONObject();
        //we do a try catch here so we needn't catch it everywhere else
        try {
            error.put("type", e.getClass().getSimpleName());
            error.put("message", e.getMessage());
        } catch (JSONException ex) {
            Log.d(LOG_TAG, "jsonify arguments exception", e);
        }
        return error;
    }

    private ArrayList<String> toList(JSONArray json) throws JSONException {
        int len = json.length();
        ArrayList<String> lst = new ArrayList<String>(len);
        for (int i = 0; i < len; i++) {
            lst.add(json.getString(i));
        }
        return lst;
    }

    private void callbackError(CallbackContext cb, String type, String message) {
        if (cb != null) {
            JSONObject error = new JSONObject();
            try {
                error.put("type", type);
                error.put("message", message);
                cb.error(error);
            } catch (JSONException ex) {
                cb.error(message);
            }
        }
    }

    private void callbackError(CallbackContext cb, String type, int response_code, String message) {
        if (cb != null) {
            JSONObject error = new JSONObject();
            try {
                error.put("type", type);
                error.put("code", response_code);
                error.put("message", message);
                cb.error(error);
            } catch (JSONException ex) {
                cb.error(message);
            }
        }
    }

    // Taken from googles trivial drive example app, seemed like it couldn't hurt
    // Workaround to bug where sometimes response codes come as Long instead of Integer
    int getResponseCodeFromIntent(Intent i) {
        Object o = i.getExtras().get("RESPONSE_CODE");
        if (o == null) {
            Log.e(LOG_TAG, "Intent with no response code, assuming OK (known issue)");
            return 0; //BILLING_RESPONSE_RESULT_OK;
        } else if (o instanceof Integer) {
            return ((Integer) o).intValue();
        } else if (o instanceof Long) {
            return (int) ((Long) o).longValue();
        } else {
            Log.e(LOG_TAG, "Unexpected type for intent response code. " + o.getClass().getName());
            throw new RuntimeException("Unexpected type for intent response code: " + o.getClass().getName());
        }
    }

    /**
     * A worker is a Runnable that catches some common exception and does a
     * precheck on billingservice.
     */
    abstract class PluginWorker implements Runnable {

        CallbackContext ctx;
        JSONArray args;

        public PluginWorker(CallbackContext ctx, JSONArray args) {
            this.ctx = ctx;
            this.args = args;
        }

        public void run() {
            if (billingService == null) {
                callbackError(ctx, "PluginWorker", "Lost connection to billing service");
                return;
            }
            try {
                doWork();
            } catch (JSONException ex) {
                Log.e(LOG_TAG, "Arguments error " + ex.getMessage(), ex);
                ctx.error(jsonify(ex));
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, "Remote exception " + ex.getMessage(), ex);
                ctx.error(jsonify(ex));
            } catch (IntentSender.SendIntentException ex) {
                Log.e(LOG_TAG, "Error sending intent " + ex.getMessage(), ex);
                ctx.error(jsonify(ex));
            }
        }

        abstract public void doWork() throws JSONException, RemoteException, IntentSender.SendIntentException;
    }
}
