/*******************************************************************************
 * This file is part of Zotable.
 *
 * Zotable is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Zotable is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Zotable.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package com.rlien.zoterand.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.support.v4.app.ListFragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.text.Editable;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.getbase.floatingactionbutton.FloatingActionButton;


import com.rlien.zoterand.app.data.Database;
import com.rlien.zoterand.app.data.Item;
import com.rlien.zoterand.app.data.ItemAdapter;
import com.rlien.zoterand.app.data.ItemCollection;
import com.rlien.zoterand.app.task.APIEvent;
import com.rlien.zoterand.app.task.APIRequest;
import com.rlien.zoterand.app.task.ZoteroAPITask;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.nononsenseapps.filepicker.FilePickerActivity;
import com.squareup.otto.Subscribe;

import org.apache.http.util.ByteArrayBuffer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;

public class Fragment_Items extends ListFragment implements SwipeRefreshLayout.OnRefreshListener {

    private static final String TAG = "Fragment_Items";

    static final int DIALOG_NEW = 1;
    static final int DIALOG_SORT = 2;
    static final int DIALOG_IDENTIFIER = 3;
    static final int DIALOG_PROGRESS = 6;

    static final int FILE_CODE = 21;

    Bundle b = new Bundle();

    /**
     * Allowed sort orderings
     */
    static final String[] SORTS = {
            "item_year, item_title COLLATE NOCASE",
            "item_creator COLLATE NOCASE, item_year",
            "item_title COLLATE NOCASE, item_year",
            "timestamp ASC, item_title COLLATE NOCASE"
    };

    /**
     * Strings providing the names of each ordering, respectively
     */
    static final int[] SORT_NAMES = {
            R.string.sort_year_title,
            R.string.sort_creator_year,
            R.string.sort_title_year,
            R.string.sort_modified_title
    };
    private static final String SORT_CHOICE = "sort_choice";

    private Database db;

    private SwipeRefreshLayout swipeLayout;

    private ProgressDialog mProgressDialog;
    private ProgressThread progressThread;

    public String sortBy = "item_year, item_title";

    final Handler syncHandler = new Handler() {
        public void handleMessage(Message msg) {
            Log.d(TAG, "received message: " + msg.arg1);
            refreshView();

            if (msg.arg1 == APIRequest.UPDATED_DATA) {
                refreshView();
                return;
            }

            if (msg.arg1 == APIRequest.QUEUED_MORE) {
                //Toast.makeText(getActivity().getApplicationContext(),getResources().getString(R.string.sync_queued_more, msg.arg2),Toast.LENGTH_SHORT).show();
                return;
            }

            if (msg.arg1 == APIRequest.BATCH_DONE) {
                Application.getInstance().getBus().post(SyncEvent.COMPLETE);

                // Sync success w/o erros -- set all items to clean status in DB
                Item.setAllClean(db);

                Toast.makeText(getActivity().getApplicationContext(),getResources().getString(R.string.sync_complete),Toast.LENGTH_SHORT).show();
                return;
            }

            if (msg.arg1 == APIRequest.ERROR_UNKNOWN) {
                Toast.makeText(getActivity().getApplicationContext(),getResources().getString(R.string.sync_error),Toast.LENGTH_SHORT).show();
                return;
            }
        }
    };

    private APIEvent mEvent = new APIEvent() {
        private int updates = 0;

        @Override
        public void onComplete(APIRequest request) {
            Message msg = syncHandler.obtainMessage();
            msg.arg1 = APIRequest.BATCH_DONE;
            syncHandler.sendMessage(msg);
            Log.d(TAG, "fired oncomplete");

            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Stop the Spinning refreshing icon
                    swipeLayout.setRefreshing(false);
                }
            });
        }

        @Override
        public void onUpdate(APIRequest request) {
            updates++;

            if (updates % 10 == 0) {
                Message msg = syncHandler.obtainMessage();
                msg.arg1 = APIRequest.UPDATED_DATA;
                syncHandler.sendMessage(msg);
            } else {
                // do nothing
            }
        }

        @Override
        public void onError(APIRequest request, Exception exception) {
            Log.e(TAG, "APIException caught", exception);
            Message msg = syncHandler.obtainMessage();
            msg.arg1 = APIRequest.ERROR_UNKNOWN;
            syncHandler.sendMessage(msg);
        }

        @Override
        public void onError(APIRequest request, int error) {
            Log.e(TAG, "API error caught");
            Message msg = syncHandler.obtainMessage();
            msg.arg1 = APIRequest.ERROR_UNKNOWN;
            syncHandler.sendMessage(msg);
        }
    };

    /**
     * Refreshes the current list adapter
     */
    private void refreshView() {
        ItemAdapter adapter = (ItemAdapter) getListAdapter();
        if (adapter == null) return;

        Cursor newCursor = prepareCursor();
        adapter.changeCursor(newCursor);
        adapter.notifyDataSetChanged();
        Log.d(TAG, "refreshing view on request");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_items, container, false);
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onStart() {
        super.onStart();

        setHasOptionsMenu(true);

        String persistedSort = Persistence.read(SORT_CHOICE);
        if (persistedSort != null) sortBy = persistedSort;

        db = new Database(getActivity());

        swipeLayout = (SwipeRefreshLayout) getActivity().findViewById(R.id.swipe_container_items);
        swipeLayout.setOnRefreshListener(this);
        swipeLayout.setColorScheme(R.color.light_blue,
                R.color.blue,
                R.color.dark_blue,
                R.color.darkest_blue);

        APIRequest req;

        req = APIRequest.fetchItems(false, new ServerCredentials(getActivity()));

        prepareAdapter();

        ItemAdapter adapter = (ItemAdapter) getListAdapter();
        Cursor cur = adapter.getCursor();

        // TODO: quick & dirty hack -- possible error here? compare with github original
        if (cur == null || cur.getCount() == 0) {

            if (!ServerCredentials.check(getActivity().getBaseContext())) {
                Toast.makeText(getActivity().getBaseContext(), getResources().getString(R.string.sync_log_in_first),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(getActivity(),getActivity().getResources().getString(R.string.collection_empty),Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Running a request to populate missing items");
            ZoteroAPITask task = new ZoteroAPITask(getActivity());
            req.setHandler(mEvent);
            task.execute(req);

        }

        final ListView lv = getListView();
        lv.setOnItemClickListener(new OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // If we have a click on an item, do something...
                ItemAdapter adapter = (ItemAdapter) parent.getAdapter();
                Cursor cur = adapter.getCursor();
                // Place the cursor at the selected item
                if (cur.moveToPosition(position)) {
                    // and load an activity for the item
                    Item item = Item.load(cur);

                    Log.d(TAG, "Loading item data with key: " + item.getKey());
                    // We create and issue a specified intent with the necessary data
                    Intent i = new Intent(getActivity().getBaseContext(), ItemDataActivity.class);
                    i.putExtra("com.rlien.zoterand.app.itemKey", item.getKey());
                    i.putExtra("com.rlien.zoterand.app.itemDbId", item.dbId);
                    startActivity(i);
                } else {
                    // failed to move cursor-- show a toast
                    TextView tvTitle = (TextView) view.findViewById(R.id.item_title);
                    Toast.makeText(getActivity().getApplicationContext(),
                            getResources().getString(R.string.cant_open_item, tvTitle.getText()),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        lv.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView absListView, int i) {}

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                int topRowVerticalPosition = (lv == null || lv.getChildCount() == 0) ? 0 : lv.getChildAt(0).getTop();

                swipeLayout.setEnabled(firstVisibleItem == 0 && topRowVerticalPosition >= 0);
            }
        });

        // Create floating action buttons
        FloatingActionButton fabScan = (FloatingActionButton)getActivity().findViewById(R.id.btnFabScan);
        FloatingActionButton fabIsbn = (FloatingActionButton)getActivity().findViewById(R.id.btnFabIsbn);
        FloatingActionButton fabUpload = (FloatingActionButton)getActivity().findViewById(R.id.btnFabUpload);
        FloatingActionButton fabManual = (FloatingActionButton)getActivity().findViewById(R.id.btnFabManual);

        fabScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                scanBarcode(Fragment_Items.this);
            }
        });

        fabIsbn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showDialog(DIALOG_IDENTIFIER);
            }
        });

        fabUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // This always works
                Intent i = new Intent(getActivity(), FilePickerActivity.class);
                // This works if you defined the intent filter
                // Intent i = new Intent(Intent.ACTION_GET_CONTENT);

                // Set these depending on your use case. These are the defaults.
                i.putExtra(FilePickerActivity.EXTRA_ALLOW_MULTIPLE, false);
                i.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, false);
                i.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_FILE);

                // Configure initial directory by specifying a String.
                // You could specify a String like "/storage/emulated/0/", but that can
                // dangerous. Always use Android's API calls to get paths to the SD-card or
                // internal memory.
                String filepath = Environment.getExternalStorageDirectory().getPath();
                if (new File("/sdcard/").exists())
                    filepath = "/sdcard/";  // prefer /sdcard... implemented this way in case DNE

                i.putExtra(FilePickerActivity.EXTRA_START_PATH, filepath);

                startActivityForResult(i, FILE_CODE);
            }
        });

        fabManual.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showDialog(DIALOG_NEW);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        Application.getInstance().getBus().register(this);
        refreshView();
    }

    @Override
    public void onDestroy() {
        ItemAdapter adapter = (ItemAdapter) getListAdapter();
        Cursor cur = null;

        if (adapter != null)
            cur = adapter.getCursor();

        if (cur != null)
            cur.close();

        if (db != null)
            db.close();

        super.onDestroy();
    }

    @Override
    public void onPause() {
        super.onPause();
        Application.getInstance().getBus().unregister(this);
    }

    @Override
    public void onRefresh() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                sync();
            }
        });
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        menu.clear();

        inflater.inflate(R.menu.zotero_menu,menu);

        // Turn on sort item
        MenuItem sort = menu.findItem(R.id.do_sort);
        sort.setEnabled(true);
        sort.setVisible(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.do_sort:
                showDialog(DIALOG_SORT);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void prepareAdapter() {
        ItemAdapter adapter = new ItemAdapter(getActivity(), prepareCursor());
        setListAdapter(adapter);
    }

    private Cursor prepareCursor() {

        // No longer searchable because it is a fragment...
        return getCursor();
/*
        Cursor cursor;
        // Be ready for a search
        Intent intent = getActivity().getIntent();

        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            query = intent.getStringExtra(SearchManager.QUERY);
            cursor = getCursor(query);
            getActivity().setTitle(getResources().getString(R.string.search_results, query));
        } else if (query != null) {
            cursor = getCursor(query);
            getActivity().setTitle(getResources().getString(R.string.search_results, query));
        } else if (intent.getStringExtra("com.mattrobertson.zotable.app.tag") != null) {
            String tag = intent.getStringExtra("com.mattrobertson.zotable.app.tag");
            Query q = new Query();
            q.set("tag", tag);
            cursor = getCursor(q);
            getActivity().setTitle(getResources().getString(R.string.tag_viewing_items, tag));
        } else {
            collectionKey = intent.getStringExtra("com.mattrobertson.zotable.app.collectionKey");

            ItemCollection coll;

            if (collectionKey != null && (coll = ItemCollection.load(collectionKey, db)) != null) {
                cursor = getCursor(coll);
                getActivity().setTitle(coll.getTitle());
            } else {
                cursor = getCursor();
                getActivity().setTitle(getResources().getString(R.string.all_items));
            }
        }
        return cursor;
*/
    }

    public void showDialog(int id) {
        switch (id) {
            case DIALOG_NEW:
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setTitle(getResources().getString(R.string.item_type))
                        // XXX i18n
                        .setItems(Item.ITEM_TYPES_EN, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int pos) {
                                Item item = new Item(getActivity().getBaseContext(), Item.ITEM_TYPES[pos]);
                                item.dirty = APIRequest.API_DIRTY;
                                item.save(db);

                                Log.d(TAG, "Loading item data with key: " + item.getKey());
                                // We create and issue a specified intent with the necessary data
                                Intent i = new Intent(getActivity().getBaseContext(), ItemDataActivity.class);
                                i.putExtra("com.mattrobertson.zotable.app.itemKey", item.getKey());
                                startActivity(i);
                            }
                        });
                AlertDialog dialog = builder.create();
                dialog.show();
                return;
            case DIALOG_SORT:

                // We generate the sort name list for our current locale
                String[] sorts = new String[SORT_NAMES.length];
                for (int j = 0; j < SORT_NAMES.length; j++) {
                    sorts[j] = getResources().getString(SORT_NAMES[j]);
                }

                AlertDialog.Builder builder2 = new AlertDialog.Builder(getActivity());
                builder2.setTitle(getResources().getString(R.string.set_sort_order))
                        .setItems(sorts, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int pos) {
                                Cursor cursor = getCursor();
                                setSortBy(SORTS[pos]);

                                ItemAdapter adapter = (ItemAdapter) getListAdapter();
                                adapter.changeCursor(cursor);
                                Log.d(TAG, "Re-sorting by: " + SORTS[pos]);

                                Persistence.write(SORT_CHOICE, SORTS[pos]);
                            }
                        });
                AlertDialog dialog2 = builder2.create();
                dialog2.show();
                return;
            case DIALOG_PROGRESS:
                mProgressDialog = new ProgressDialog(getActivity());
                mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                mProgressDialog.setIndeterminate(true);
                mProgressDialog.setMessage(getResources().getString(R.string.identifier_looking_up));
                mProgressDialog.show();
                return;
            case DIALOG_IDENTIFIER:
                final EditText input = new EditText(getActivity());
                input.setHint(getResources().getString(R.string.identifier_hint));
                input.setInputType(InputType.TYPE_CLASS_NUMBER);

                final Fragment_Items current = this;

                dialog = new AlertDialog.Builder(getActivity())
                        .setTitle(getResources().getString(R.string.identifier_message))
                        .setView(input)
                        .setPositiveButton(getResources().getString(R.string.menu_search), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                Editable value = input.getText();
                                // run search
                                Bundle c = new Bundle();
                                c.putString("mode", "isbn");
                                c.putString("identifier", value.toString());

                                // TODO: quick & dirty fix... check here if error occurs
                                Fragment_Items.this.b = c;

                                Log.d(TAG, b.getString("identifier"));
                                progressThread = new ProgressThread(handler, b);
                                progressThread.start();

                                showDialog(DIALOG_PROGRESS);
                            }
                        }).setNegativeButton(getResources().getString(R.string.cancel), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                // do nothing
                            }
                        }).create();
                    dialog.show();
                return;
        }
    }

    /* Sorting */
    public void setSortBy(String sort) {
        this.sortBy = sort;
    }

    /* Handling the ListView and keeping it up to date */
    public Cursor getCursor() {
        Cursor cursor = db.query("items", Database.ITEMCOLS, null, null, null, null, this.sortBy, null);
        if (cursor == null) {
            Log.e(TAG, "cursor is null");
        }
        return cursor;
    }

    public Cursor getCursor(ItemCollection parent) {
        String[] args = {parent.dbId};
        Cursor cursor = db.rawQuery("SELECT item_title, item_type, item_content, etag, dirty, " +
                        "items._id, item_key, item_year, item_creator, timestamp, item_children " +
                        " FROM items, itemtocollections WHERE items._id = item_id AND collection_id=? ORDER BY " + this.sortBy,
                args);
        if (cursor == null) {
            Log.e(TAG, "cursor is null");
        }
        return cursor;
    }

    public Cursor getCursor(String query) {
        String[] args = {"%" + query + "%", "%" + query + "%"};
        Cursor cursor = db.rawQuery("SELECT item_title, item_type, item_content, etag, dirty, " +
                        "_id, item_key, item_year, item_creator, timestamp, item_children " +
                        " FROM items WHERE item_title LIKE ? OR item_creator LIKE ?" +
                        " ORDER BY " + this.sortBy,
                args);
        if (cursor == null) {
            Log.e(TAG, "cursor is null");
        }
        return cursor;
    }

    public Cursor getCursor(Query query) {
        return query.query(db);
    }

    @Subscribe
    public void syncComplete(SyncEvent event) {
        if (event.getStatus() == SyncEvent.COMPLETE_CODE) refreshView();
    }

	/* Thread and helper to run lookups */

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        Log.d(TAG, "_____________________on_activity_result");

        if (requestCode == FILE_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Uri uri = intent.getData();
                Util.handleUpload(uri.getPath(),getActivity(),db,false);
            }
        }
        else {
            IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
            if (scanResult != null) {
                // handle scan result
                Bundle b = new Bundle();
                b.putString("mode", "isbn");
                b.putString("identifier", scanResult.getContents());

                if (scanResult != null && scanResult.getContents() != null) {
                    Log.d(TAG, b.getString("identifier"));
                    progressThread = new ProgressThread(handler, b);
                    progressThread.start();
                    showDialog(DIALOG_PROGRESS);
                } else {
                    Toast.makeText(getActivity().getApplicationContext(),
                            getResources().getString(R.string.identifier_scan_failed),
                            Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(getActivity().getApplicationContext(),
                        getResources().getString(R.string.identifier_scan_failed),
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    final Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            Log.d(TAG, "______________________handle_message");
            if (ProgressThread.STATE_DONE == msg.arg2) {
                Bundle data = msg.getData();
                String itemKey = data.getString("itemKey");
                if (itemKey != null) {

                    mProgressDialog.dismiss();
                    mProgressDialog = null;

                    Log.d(TAG, "Loading new item data with key: " + itemKey);
                    // We create and issue a specified intent with the necessary data
                    Intent i = new Intent(getActivity().getBaseContext(), ItemDataActivity.class);
                    i.putExtra("com.mattrobertson.zotable.app.itemKey", itemKey);
                    startActivity(i);
                }
                return;
            }

            if (ProgressThread.STATE_PARSING == msg.arg2) {
                if (mProgressDialog != null)
                    mProgressDialog.setMessage(getResources().getString(R.string.identifier_processing));
                return;
            }

            if (ProgressThread.STATE_ERROR == msg.arg2) {
                getActivity().dismissDialog(DIALOG_PROGRESS);
                Toast.makeText(getActivity().getApplicationContext(),
                        getResources().getString(R.string.identifier_lookup_failed),
                        Toast.LENGTH_SHORT).show();
                progressThread.setState(ProgressThread.STATE_DONE);
                return;
            }
        }
    };

    private class ProgressThread extends Thread {
        Handler mHandler;
        Bundle arguments;
        final static int STATE_DONE = 5;
        final static int STATE_FETCHING = 1;
        final static int STATE_PARSING = 6;
        final static int STATE_ERROR = 7;

        int mState;

        ProgressThread(Handler h, Bundle b) {
            mHandler = h;
            arguments = b;
            Log.d(TAG, "_____________________thread_constructor");
        }

        public void run() {
            Log.d(TAG, "_____________________thread_run");
            mState = STATE_FETCHING;

            // Setup
            String identifier = arguments.getString("identifier");
            String mode = arguments.getString("mode");
            URL url;
            String urlstring;

            String response = "";

            if ("isbn".equals(mode)) {
                urlstring = "http://xisbn.worldcat.org/webservices/xid/isbn/"
                        + identifier
                        + "?method=getMetadata&fl=*&format=json&count=1";
            } else {
                urlstring = "";
            }

            try {
                Log.d(TAG, "Fetching from: " + urlstring);
                url = new URL(urlstring);
                
                /* Open a connection to that URL. */
                URLConnection ucon = url.openConnection();                
                /*
                 * Define InputStreams to read from the URLConnection.
                 */
                InputStream is = ucon.getInputStream();
                BufferedInputStream bis = new BufferedInputStream(is, 16000);

                ByteArrayBuffer baf = new ByteArrayBuffer(50);
                int current = 0;
                
                /*
                 * Read bytes to the Buffer until there is nothing more to read(-1).
                 */
                while (mState == STATE_FETCHING
                        && (current = bis.read()) != -1) {
                    baf.append((byte) current);
                }
                response = new String(baf.toByteArray());
                Log.d(TAG, response);


            } catch (IOException e) {
                Log.e(TAG, "Error: ", e);
            }

            Message msg = mHandler.obtainMessage();
            msg.arg2 = STATE_PARSING;
            mHandler.sendMessage(msg);

        	/*
             * {
 "stat":"ok",
 "list":[{
	"url":["http://www.worldcat.org/oclc/177669176?referer=xid"],
	"publisher":"O'Reilly",
	"form":["BA"],
	"lccn":["2004273129"],
	"lang":"eng",
	"city":"Sebastopol, CA",
	"author":"by Mark Lutz and David Ascher.",
	"ed":"2nd ed.",
	"year":"2003",
	"isbn":["0596002815"],
	"title":"Learning Python",
	"oclcnum":["177669176",
..
	 "748093898"]}]}
        	 */

            // This is OCLC-specific logic
            try {
                JSONObject result = new JSONObject(response);

                if (!result.getString("stat").equals("ok")) {
                    Log.e(TAG, "Error response received");
                    msg = mHandler.obtainMessage();
                    msg.arg2 = STATE_ERROR;
                    mHandler.sendMessage(msg);
                    return;
                }

                result = result.getJSONArray("list").getJSONObject(0);
                String form = result.getJSONArray("form").getString(0);
                String type;

                if ("AA".equals(form)) type = "audioRecording";
                else if ("VA".equals(form)) type = "videoRecording";
                else if ("FA".equals(form)) type = "film";
                else type = "book";

                // TODO Fix this
                type = "book";

                Item item = new Item(getActivity().getBaseContext(), type);

                JSONObject content = item.getContent();

                if (result.has("lccn")) {
                    String lccn = "LCCN: " + result.getJSONArray("lccn").getString(0);
                    content.put("extra", lccn);
                }

                if (result.has("isbn")) {
                    content.put("ISBN", result.getJSONArray("isbn").getString(0));
                }

                content.put("title", result.optString("title", ""));
                content.put("place", result.optString("city", ""));
                content.put("edition", result.optString("ed", ""));
                content.put("language", result.optString("lang", ""));
                content.put("publisher", result.optString("publisher", ""));
                content.put("date", result.optString("year", ""));

                item.setTitle(result.optString("title", ""));
                item.setYear(result.optString("year", ""));

                String author = result.optString("author", "");

                item.setCreatorSummary(author);
                JSONArray array = new JSONArray();
                JSONObject member = new JSONObject();
                member.accumulate("creatorType", "author");
                member.accumulate("name", author);
                array.put(member);
                content.put("creators", array);

                item.setContent(content);   // NOTE: tags item as DIRTY
                item.save(db);

                msg = mHandler.obtainMessage();
                Bundle data = new Bundle();
                data.putString("itemKey", item.getKey());
                msg.setData(data);
                msg.arg2 = STATE_DONE;
                mHandler.sendMessage(msg);
                return;
            } catch (JSONException e) {
                Log.e(TAG, "exception parsing response", e);
                msg = mHandler.obtainMessage();
                msg.arg2 = STATE_ERROR;
                mHandler.sendMessage(msg);
                return;
            }
        }

        public void setState(int state) {
            mState = state;
        }
    }

    public void sync() {
        // Check log-in -- prompt if not logged in
        if (!ServerCredentials.check(getActivity().getBaseContext())) {
            Toast.makeText(getActivity().getBaseContext(), getResources().getString(R.string.sync_log_in_first),Toast.LENGTH_SHORT).show();
            return;
        }

        // Get credentials
        ServerCredentials cred = new ServerCredentials(getActivity().getBaseContext());

        // OLD COMMENT: Make this a collection-specific sync, preceding by de-dirtying

        Item.buildDirtyQueue(db);
        ArrayList<APIRequest> list = new ArrayList<APIRequest>();

        for (Item i : Item.dirtyQueue) {
            Log.d(TAG, "Adding dirty item to sync: " + i.getTitle());
            list.add(cred.prep(APIRequest.update(i)));
        }

        Log.d(TAG, "Adding sync request for all items");
        APIRequest req = APIRequest.fetchItems(false, cred);
        req.setHandler(mEvent);
        list.add(req);

        APIRequest[] templ = {};    // to ensure toArray converts to APIRequests, not Objects(?)
        APIRequest[] reqs = list.toArray(templ);

        ZoteroAPITask task = new ZoteroAPITask(getActivity().getBaseContext());
        task.setHandler(syncHandler);
        task.execute(reqs);
        //Toast.makeText(getApplicationContext(), getResources().getString(R.string.sync_started),Toast.LENGTH_SHORT).show();
    }

    public void scanBarcode(Fragment_Items current) {
        // If we're about to download from Google play, cancel that dialog
        // and prompt from Amazon if we're on an Amazon device
        FragmentIntentIntegrator integrator = new FragmentIntentIntegrator(this);
        AlertDialog producedDialog = integrator.initiateScan();
        if (producedDialog != null && "amazon".equals(BuildConfig.FLAVOR)) {
            producedDialog.dismiss();
            AmazonZxingGlue.showDownloadDialog(current.getActivity());
        }
    }

    private final class FragmentIntentIntegrator extends IntentIntegrator {

        private final Fragment fragment;

        public FragmentIntentIntegrator(Fragment fragment) {
            super(fragment.getActivity());
            this.fragment = fragment;
        }

        @Override
        protected void startActivityForResult(Intent intent, int code) {
            fragment.startActivityForResult(intent, code);
        }
    }
}