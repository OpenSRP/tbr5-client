package org.smartregister.growthmonitoring.service.intent;

import android.app.IntentService;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.joda.time.DateTime;
import org.opensrp.api.constants.Gender;
import org.smartregister.commonregistry.CommonPersonObject;
import org.smartregister.commonregistry.CommonPersonObjectClient;
import org.smartregister.growthmonitoring.GrowthMonitoringLibrary;
import org.smartregister.growthmonitoring.domain.Weight;
import org.smartregister.growthmonitoring.domain.ZScore;
import org.smartregister.growthmonitoring.repository.ZScoreRepository;
import org.smartregister.growthmonitoring.util.GMConstants;
import org.smartregister.util.FileUtilities;
import org.smartregister.util.Utils;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * Created by Jason Rogena - jrogena@ona.io on 30/05/2017.
 */

public class ZScoreRefreshIntentService extends IntentService {
    private static final String TAG = ZScoreRefreshIntentService.class.getName();
    private static final Map<String, String> CSV_HEADING_SQL_COLUMN_MAP;

    static {
        CSV_HEADING_SQL_COLUMN_MAP = new HashMap<>();
        CSV_HEADING_SQL_COLUMN_MAP.put("Month", ZScoreRepository.COLUMN_MONTH);
        CSV_HEADING_SQL_COLUMN_MAP.put("L", ZScoreRepository.COLUMN_L);
        CSV_HEADING_SQL_COLUMN_MAP.put("M", ZScoreRepository.COLUMN_M);
        CSV_HEADING_SQL_COLUMN_MAP.put("S", ZScoreRepository.COLUMN_S);
        CSV_HEADING_SQL_COLUMN_MAP.put("SD3neg", ZScoreRepository.COLUMN_SD3NEG);
        CSV_HEADING_SQL_COLUMN_MAP.put("SD2neg", ZScoreRepository.COLUMN_SD2NEG);
        CSV_HEADING_SQL_COLUMN_MAP.put("SD1neg", ZScoreRepository.COLUMN_SD1NEG);
        CSV_HEADING_SQL_COLUMN_MAP.put("SD0", ZScoreRepository.COLUMN_SD0);
        CSV_HEADING_SQL_COLUMN_MAP.put("SD1", ZScoreRepository.COLUMN_SD1);
        CSV_HEADING_SQL_COLUMN_MAP.put("SD2", ZScoreRepository.COLUMN_SD2);
        CSV_HEADING_SQL_COLUMN_MAP.put("SD3", ZScoreRepository.COLUMN_SD3);
    }

    public ZScoreRefreshIntentService() {
        super(TAG);
    }

    /**
     * Creates an IntentService.  Invoked by your subclass's constructor.
     *
     * @param name Used to name the worker thread, important only for debugging.
     */
    public ZScoreRefreshIntentService(String name) {
        super(name);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        // Dump CSV to file
        dumpCsv(Gender.MALE, false);
        dumpCsv(Gender.FEMALE, false);


        calculateChildZScores();

        //FIXME split-growth-monitoring:Calling hia2Service after calculating zscore
        //Intent hia2Intent = new Intent(GrowthMonitoringLibrary.getInstance(), HIA2IntentService.class);
        //startService(hia2Intent);
    }

    private void fetchCSV(Gender gender) {
        String urlString = null;
        if (gender.equals(Gender.FEMALE)) {
            urlString = GMConstants.ZSCORE_FEMALE_URL;
        } else if (gender.equals(Gender.MALE)) {
            urlString = GMConstants.ZSCORE_MALE_URL;
        }

        try {
            URL url;

            url = new URL(urlString);
            URLConnection urlConnection = null;

            int responseCode = 0;
            if (url.getProtocol().equalsIgnoreCase("https")) {
                urlConnection = (HttpsURLConnection) url.openConnection();

                // Sets the user agent for this request.
                urlConnection.setRequestProperty("User-Agent", FileUtilities.getUserAgent(GrowthMonitoringLibrary.getInstance().context().applicationContext()));

                // Gets a response code from the RSS server
                responseCode = ((HttpsURLConnection) urlConnection).getResponseCode();

            } else if (url.getProtocol().equalsIgnoreCase("http")) {
                urlConnection = (HttpURLConnection) url.openConnection();

                // Sets the user agent for this request.
                urlConnection.setRequestProperty("User-Agent", FileUtilities.getUserAgent(GrowthMonitoringLibrary.getInstance().context().applicationContext()));

                // Gets a response code from the RSS server
                responseCode = ((HttpsURLConnection) urlConnection).getResponseCode();
            }

            switch (responseCode) {
                // If the response is OK
                case HttpURLConnection.HTTP_OK:
                    // Gets the last modified data for the URL
                    processResponse(urlConnection, gender);
                    break;
                default:
                    Log.e(TAG, "Response code " + responseCode + " returned for Z-Score fetch from " + urlString);
                    break;
            }


        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }

    }

    /**
     * This method dumps the ZScore CSV corresponding to the provided gender into the z_score table
     *
     * @param gender
     * @param force
     */
    private void dumpCsv(Gender gender, boolean force) {
        try {
            List<ZScore> existingScores = GrowthMonitoringLibrary.getInstance().zScoreRepository().findWeightForAgeByGender(gender);
            if (force
                    || existingScores.size() == 0) {
                String filename = null;
                if (gender.equals(Gender.FEMALE)) {
                    filename = GrowthMonitoringLibrary.getInstance().getConfig().getFemaleWeightForAgeZScoreFile();
                } else if (gender.equals(Gender.MALE)) {
                    filename = GrowthMonitoringLibrary.getInstance().getConfig().getMaleWeightForAgeZScoreFile();
                }

                if (filename != null) {
                    CSVParser csvParser = CSVParser.parse(Utils.readAssetContents(this, filename),
                            CSVFormat.newFormat('\t'));

                    HashMap<Integer, Boolean> columnStatus = new HashMap<>();
                    String query = "INSERT INTO `" + ZScoreRepository.TABLE_NAME_WEIGHT_FOR_AGE + "` ( `" + ZScoreRepository.COLUMN_SEX + "`";
                    for (CSVRecord record : csvParser) {
                        if (csvParser.getCurrentLineNumber() == 2) {// The second line
                            query = query + ")\n VALUES (\"" + gender.name() + "\"";
                        } else if (csvParser.getCurrentLineNumber() > 2) {
                            query = query + "),\n (\"" + gender.name() + "\"";
                        }

                        for (int columnIndex = 0; columnIndex < record.size(); columnIndex++) {
                            String curColumn = record.get(columnIndex);
                            if (csvParser.getCurrentLineNumber() == 1) {
                                if (CSV_HEADING_SQL_COLUMN_MAP.containsKey(curColumn)) {
                                    columnStatus.put(columnIndex, true);
                                    query = query + ", `" + CSV_HEADING_SQL_COLUMN_MAP.get(curColumn) + "`";
                                } else {
                                    columnStatus.put(columnIndex, false);
                                }
                            } else {
                                if (columnStatus.get(columnIndex)) {
                                    query = query + ", \"" + curColumn + "\"";
                                }
                            }
                        }
                    }
                    query = query + ");";

                    boolean result = GrowthMonitoringLibrary.getInstance().zScoreRepository().runRawQuery(query);
                    Log.d(TAG, "Result is " + result);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    /**
     * This method retrieves all weight records that don't have ZScores and tries to calculate their
     * corresponding ZScores
     */
    private void calculateChildZScores() {
        try {
            HashMap<String, CommonPersonObjectClient> children = new HashMap<>();
            List<Weight> weightsWithoutZScores = GrowthMonitoringLibrary.getInstance().weightRepository().findWithNoZScore();
            for (Weight curWeight : weightsWithoutZScores) {
                if (!TextUtils.isEmpty(curWeight.getBaseEntityId())) {
                    if (!children.containsKey(curWeight.getBaseEntityId())) {
                        CommonPersonObjectClient childDetails = getChildDetails(curWeight.getBaseEntityId());
                        children.put(curWeight.getBaseEntityId(), childDetails);
                    }

                    CommonPersonObjectClient curChild = children.get(curWeight.getBaseEntityId());

                    if (curChild != null) {
                        Gender gender = Gender.UNKNOWN;
                        String genderString = Utils.getValue(curChild.getColumnmaps(), "gender", false);
                        if (genderString != null && genderString.equalsIgnoreCase("female")) {
                            gender = Gender.FEMALE;
                        } else if (genderString != null && genderString.equalsIgnoreCase("male")) {
                            gender = Gender.MALE;
                        }

                        Date dob = null;
                        String dobString = Utils.getValue(curChild.getColumnmaps(), "dob", false);
                        if (!TextUtils.isEmpty(dobString)) {
                            DateTime dateTime = new DateTime(dobString);
                            dob = dateTime.toDate();
                        }

                        if (gender != Gender.UNKNOWN && dob != null) {
                            GrowthMonitoringLibrary.getInstance().weightRepository().add(dob, gender, curWeight);
                        } else {
                            Log.w(TAG, "Could not get the date of birth or gender for child with base entity id " + curWeight.getBaseEntityId());
                        }
                    } else {
                        Log.w(TAG, "Could not get the details for child with base entity id " + curWeight.getBaseEntityId());
                    }
                } else {
                    Log.w(TAG, "Current weight with id " + curWeight.getId() + " has no base entity id");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    private CommonPersonObjectClient getChildDetails(String baseEntityId) {
        CommonPersonObject rawDetails = GrowthMonitoringLibrary.getInstance().context()
                .commonrepository(GMConstants.CHILD_TABLE_NAME).findByBaseEntityId(baseEntityId);
        if (rawDetails != null) {
            // Get extra child details
            CommonPersonObjectClient childDetails = Utils.convert(rawDetails);
            childDetails.getColumnmaps().putAll(GrowthMonitoringLibrary.getInstance().context()
                    .detailsRepository().getAllDetailsForClient(baseEntityId));

            return childDetails;
        }

        return null;
    }

    private void processResponse(URLConnection urlConnection, Gender gender) {
        // TODO: write file to asset folder
        //String response = readInputStreamToString(urlConnection);
    }

    /**
     * @param connection object; note: before calling this function,
     *                   ensure that the connection is already be open, and any writes to
     *                   the connection's output stream should have already been completed.
     * @return String containing the body of the connection response or
     * null if the input stream could not be read correctly
     */
    private String readInputStreamToString(URLConnection connection) {
        String result = null;
        StringBuffer sb = new StringBuffer();
        InputStream is = null;

        try {
            is = new BufferedInputStream(connection.getInputStream());
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String inputLine = "";
            while ((inputLine = br.readLine()) != null) {
                sb.append(inputLine);
            }
            result = sb.toString();
        } catch (Exception e) {
            Log.i(TAG, "Error reading InputStream");
            result = null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    Log.i(TAG, "Error closing InputStream");
                }
            }
        }

        return result;
    }
}
