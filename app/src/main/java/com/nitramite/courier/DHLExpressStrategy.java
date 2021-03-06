package com.nitramite.courier;

import android.annotation.SuppressLint;
import android.util.Log;

import com.nitramite.paketinseuranta.EventObject;
import com.nitramite.paketinseuranta.PhaseNumber;
import com.nitramite.utils.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@SuppressWarnings("HardCodedStringLiteral")
public class DHLExpressStrategy implements CourierStrategy {

    // Logging
    private static final String TAG = DHLExpressStrategy.class.getSimpleName();


    @Override
    public ParcelObject execute(String parcelCode, final com.nitramite.utils.Locale locale) {
        ParcelObject parcelObject = new ParcelObject(parcelCode);
        ArrayList<EventObject> eventObjects = new ArrayList<>();
        try {
            String url = "https://www.dhl.fi/shipmentTracking?AWB=" + parcelCode + "&countryCode=fi&languageCode=" + (locale == com.nitramite.utils.Locale.FI ? "fi" : "en");

            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                    .addHeader("Accept-Encoding", "gzip, deflate, br")
                    .addHeader("Accept-Language", "fi,en-US;q=0.9,en;q=0.8,de;q=0.7")
                    .addHeader("Connection", "keep-alive")
                    .addHeader("Host", "www.dhl.fi")
                    .addHeader("Referer", "https://www.dhl.fi/exp-fi/express/lahetysten_seuranta.html?AWB=" + parcelCode + "&brand=DHL")
                    .addHeader("User-Agent", Constants.UserAgent)
                    .build();
            Response response = client.newCall(request).execute();
            String jsonResult = response.body().string();

            // Parsing got json content
            JSONObject jsonResponse = new JSONObject(jsonResult);                       // Json content
            JSONArray jsonMainNode = jsonResponse.optJSONArray("results");              // Get "results" array
            JSONObject jsonChildNode = jsonMainNode.getJSONObject(0);                   // Get first object from "results" array
            Log.i(TAG, jsonChildNode.toString()); // Print whole response


            if (jsonChildNode.length() > 0) {
                parcelObject.setIsFound(true); // Parcel is found

                // Parse all package related normal data found
                if (jsonChildNode.has("delivery")) {
                    String phase = jsonChildNode.getJSONObject("delivery").getString("status").toUpperCase(Locale.getDefault());
                    if (phase.equals(PhaseNumber.PHASE_TRANSIT)) {
                        phase = PhaseNumber.PHASE_IN_TRANSPORT;
                    }
                    parcelObject.setPhase(phase); // Phase
                } else {
                    parcelObject.setPhase(PhaseNumber.PHASE_IN_TRANSPORT); // Set as in transport phase since it's still coming
                }
                parcelObject.setDestinationCountry(jsonChildNode.getJSONObject("destination").getString("value")); // Destination
                parcelObject.setProduct(jsonChildNode.getString("label"));
                parcelObject.setRecipientSignature(jsonChildNode.getString("description"));


                // Try to parse estimate delivery time
                try {
                    if (jsonChildNode.has("edd")) {
                        @SuppressLint("SimpleDateFormat") DateFormat tf1 = new SimpleDateFormat("yyyy-MM-dd");
                        @SuppressLint("SimpleDateFormat") DateFormat tf2 = new SimpleDateFormat("dd.MM.yyyy");
                        final JSONObject deliveryTimeObject = jsonChildNode.getJSONObject("edd");
                        String date = deliveryTimeObject.optString("date");
                        String monthNumber = Utils.monthStringToMonthNumber(date);

                        String dayNumber = date.replace(" ", "");
                        dayNumber = replaceMonths(dayNumber);
                        dayNumber = dayNumber.split(",")[1];

                        String yearNumber = date.replace(" ", "").split(",")[2];
                        Date estimateDeliveryDate = tf1.parse(yearNumber + "-" + monthNumber + "-" + dayNumber);
                        //Log.i(TAG, yearNumber + "-" + monthNumber + "-" + dayNumber);
                        parcelObject.setEstimatedDeliveryTime(tf2.format(estimateDeliveryDate));
                    }
                } catch (Exception e) {
                    Log.i(TAG, e.toString());
                }


                // Parse events
                JSONArray checkPoints = jsonChildNode.getJSONArray("checkpoints");
                // Declare time formats here
                @SuppressLint("SimpleDateFormat") DateFormat apiDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                @SuppressLint("SimpleDateFormat") DateFormat showingDateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
                @SuppressLint("SimpleDateFormat") DateFormat SQLiteDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (int i = 0; i < checkPoints.length(); i++) {
                    JSONObject checkPointObject = checkPoints.getJSONObject(i);
                    // Description
                    String description = checkPointObject.getString("description");
                    // Date
                    // example: "maanantai, heinäkuu 16, 2018 "
                    String date = checkPointObject.getString("date");
                    String monthNumber = Utils.monthStringToMonthNumber(date);
                    String dayNumber = date.replace(" ", "");
                    dayNumber = replaceMonths(dayNumber);
                    dayNumber = dayNumber.split(",")[1];
                    String yearNumber = date.replace(" ", "").split(",")[2];
                    String time = checkPointObject.getString("time");
                    Date apiDate = apiDateFormat.parse(yearNumber + "-" + monthNumber + "-" + dayNumber + " " + time);
                    String parsedShowingDate = showingDateFormat.format(apiDate);
                    String parsedDateSQLiteFormat = SQLiteDateFormat.format(apiDate);
                    String locationName = checkPointObject.getString("location");
                    EventObject eventObject = new EventObject(
                            description, parsedShowingDate, parsedDateSQLiteFormat, "", locationName
                    );
                    eventObjects.add(eventObject);
                }
                parcelObject.setEventObjects(eventObjects); // Set event object into parcel object for later fetching
            } else {
                Log.i(TAG, "DHL shipment not found");
                parcelObject.setIsFound(false); // Parcel not found
            }
        } catch (JSONException e) {
            e.printStackTrace();
            Log.i(TAG, e.toString());
        } catch (IOException | ParseException | NullPointerException | IllegalArgumentException e) {
            e.printStackTrace();
        }
        return parcelObject;
    }


    private String replaceMonths(String input) {
        return input
                .replace("tammikuu", "")
                .replace("helmikuu", "")
                .replace("maaliskuu", "")
                .replace("huhtikuu", "")
                .replace("toukokuu", "")
                .replace("kesäkuu", "")
                .replace("heinäkuu", "")
                .replace("elokuu", "")
                .replace("syyskuu", "")
                .replace("lokakuu", "")
                .replace("marraskuu", "")
                .replace("joulukuu", "")
                .replace("January", "")
                .replace("February", "")
                .replace("March", "")
                .replace("April", "")
                .replace("May", "")
                .replace("June", "")
                .replace("July", "")
                .replace("August", "")
                .replace("September", "")
                .replace("October", "")
                .replace("November", "")
                .replace("December", "");
    }


} // End of class
