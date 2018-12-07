package com.fsck.k9.activity;


import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.ActionBar;
import android.text.TextUtils.TruncateAt;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.fsck.k9.Account;
import com.fsck.k9.Account.FolderMode;
import com.fsck.k9.DI;
import com.fsck.k9.FontSizes;
import com.fsck.k9.K9;
import com.fsck.k9.Preferences;
import com.fsck.k9.activity.setup.FolderSettings;
import com.fsck.k9.controller.MessagingController;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalStoreProvider;
import com.fsck.k9.service.MailService;
import com.fsck.k9.ui.R;
import de.cketti.library.changelog.ChangeLog;
import timber.log.Timber;


public class ManageFoldersActivity extends K9ListActivity {
    private static final String EXTRA_ACCOUNT = "account";
    private static final String EXTRA_FROM_SHORTCUT = "fromShortcut";

    private static final boolean REFRESH_REMOTE = true;

    private FolderListAdapter adapter;
    private LayoutInflater inflater;
    private Account account;
    private FolderListHandler handler = new FolderListHandler();
    private FontSizes fontSizes = K9.getFontSizes();
    private Context context;
    private MenuItem refreshMenuItem;
    private View actionBarProgressView;
    private ActionBar actionBar;

    class FolderListHandler extends Handler {
        void newFolders(final List<FolderInfoHolder> newFolders) {
            runOnUiThread(new Runnable() {
                public void run() {
                    adapter.mFolders.clear();
                    adapter.mFolders.addAll(newFolders);
                    adapter.mFilteredFolders = adapter.mFolders;
                    handler.dataChanged();
                }
            });
        }

        void workingAccount(final int res) {
            runOnUiThread(new Runnable() {
                public void run() {
                    String toastText = getString(res, account.getDescription());
                    Toast toast = Toast.makeText(getApplication(), toastText, Toast.LENGTH_SHORT);
                    toast.show();
                }
            });
        }

        public void progress(final boolean progress) {
            // Make sure we don't try this before the menu is initialized
            // this could happen while the activity is initialized.
            if (refreshMenuItem == null) {
                return;
            }

            runOnUiThread(new Runnable() {
                public void run() {
                    if (progress) {
                        refreshMenuItem.setActionView(actionBarProgressView);
                    } else {
                        refreshMenuItem.setActionView(null);
                    }
                }
            });

        }

        void dataChanged() {
            runOnUiThread(new Runnable() {
                public void run() {
                    adapter.notifyDataSetChanged();
                }
            });
        }
    }

    /**
    * This class is responsible for reloading the list of local messages for a
    * given folder, notifying the adapter that the message have been loaded and
    * queueing up a remote update of the folder.
     */

    public static Intent actionHandleAccountIntent(Context context, Account account, boolean fromShortcut) {
        Intent intent = new Intent(context, ManageFoldersActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_ACCOUNT, account.getUuid());

        if (fromShortcut) {
            intent.putExtra(EXTRA_FROM_SHORTCUT, true);
        }

        return intent;
    }

    public static void actionHandleAccount(Context context, Account account) {
        Intent intent = actionHandleAccountIntent(context, account, false);
        context.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (UpgradeDatabases.actionUpgradeDatabases(this, getIntent())) {
            finish();
            return;
        }

        actionBarProgressView = getActionBarProgressView();
        actionBar = getSupportActionBar();
        initializeActionBar();
        setContentView(R.layout.folder_list);
        ListView listView = getListView();
        listView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        listView.setLongClickable(true);
        listView.setFastScrollEnabled(true);
        listView.setScrollingCacheEnabled(false);
        listView.setOnItemClickListener(new OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onClickFolder(((FolderInfoHolder) adapter.getItem(position)).serverId);
            }
        });

        listView.setSaveEnabled(true);

        inflater = getLayoutInflater();

        context = this;

        onNewIntent(getIntent());

        if (isFinishing()) {
            return;
        }

        ChangeLog cl = new ChangeLog(this);
        if (cl.isFirstRun()) {
            cl.getLogDialog().show();
        }
    }

    @SuppressLint("InflateParams")
    private View getActionBarProgressView() {
        return getLayoutInflater().inflate(R.layout.actionbar_indeterminate_progress_actionview, null);
    }

    private void initializeActionBar() {
        actionBar.setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public void onNewIntent(Intent intent) {
        setIntent(intent); // onNewIntent doesn't autoset our "internal" intent

        String accountUuid = intent.getStringExtra(EXTRA_ACCOUNT);
        account = Preferences.getPreferences(this).getAccount(accountUuid);

        if (account == null) {
            /*
             * This can happen when a launcher shortcut is created for an
             * account, and then the account is deleted or data is wiped, and
             * then the shortcut is used.
             */
            finish();
            return;
        }

        if (intent.getBooleanExtra(EXTRA_FROM_SHORTCUT, false) && account.getAutoExpandFolder() != null) {
            onClickFolder(account.getAutoExpandFolder());
            finish();
        } else {
            initializeActivityView();
        }
    }

    private void initializeActivityView() {
        adapter = new FolderListAdapter();
        restorePreviousData();

        setListAdapter(adapter);
        getListView().setTextFilterEnabled(adapter.getFilter() != null); // should never be false but better safe then sorry
    }

    @SuppressWarnings("unchecked")
    private void restorePreviousData() {
        final Object previousData = getLastCustomNonConfigurationInstance();

        if (previousData != null) {
            adapter.mFolders = (ArrayList<FolderInfoHolder>) previousData;
            adapter.mFilteredFolders = Collections.unmodifiableList(adapter.mFolders);
        }
    }


    @Override public Object onRetainCustomNonConfigurationInstance() {
        return (adapter == null) ? null : adapter.mFolders;
    }

    @Override public void onPause() {
        super.onPause();
        MessagingController.getInstance(getApplication()).removeListener(adapter.mListener);
        adapter.mListener.onPause(this);
    }

    /**
    * On resume we refresh the folder list (in the background) and we refresh the
    * messages for any folder that is currently open. This guarantees that things
    * like unread message count and read status are updated.
     */
    @Override public void onResume() {
        super.onResume();

        if (!account.isAvailable(this)) {
            Timber.i("account unavaliabale, not showing folder-list but account-list");
            Accounts.listAccounts(this);
            finish();
            return;
        }
        if (adapter == null)
            initializeActivityView();

        MessagingController.getInstance(getApplication()).addListener(adapter.mListener);
        //account.refresh(Preferences.getPreferences(this));
        MessagingController.getInstance(getApplication()).getAccountStats(this, account, adapter.mListener);

        onRefresh(!REFRESH_REMOTE);

        MessagingController.getInstance(getApplication()).cancelNotificationsForAccount(account);
        adapter.mListener.onResume(this);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_Q: {
                finish();
                return true;
            }

            case KeyEvent.KEYCODE_H: {
                Toast toast = Toast.makeText(this, R.string.folder_list_help_key, Toast.LENGTH_LONG);
                toast.show();
                return true;
            }

            case KeyEvent.KEYCODE_1: {
                setDisplayMode(FolderMode.FIRST_CLASS);
                return true;
            }
            case KeyEvent.KEYCODE_2: {
                setDisplayMode(FolderMode.FIRST_AND_SECOND_CLASS);
                return true;
            }
            case KeyEvent.KEYCODE_3: {
                setDisplayMode(FolderMode.NOT_SECOND_CLASS);
                return true;
            }
            case KeyEvent.KEYCODE_4: {
                setDisplayMode(FolderMode.ALL);
                return true;
            }
        }


        return super.onKeyDown(keyCode, event);
    }

    private void setDisplayMode(FolderMode newMode) {
        account.setFolderDisplayMode(newMode);
        Preferences.getPreferences(getApplicationContext()).saveAccount(account);
        if (account.getFolderPushMode() != FolderMode.NONE) {
            MailService.actionRestartPushers(this, null);
        }
        adapter.getFilter().filter(null);
        onRefresh(false);
    }


    private void onRefresh(final boolean forceRemote) {
        MessagingController.getInstance(getApplication()).listFolders(account, forceRemote, adapter.mListener);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.list_folders) {
            onRefresh(REFRESH_REMOTE);
            return true;
        } else if (id == R.id.compact) {
            onCompact(account);
            return true;
        } else if (id == R.id.display_1st_class) {
            setDisplayMode(FolderMode.FIRST_CLASS);
            return true;
        } else if (id == R.id.display_1st_and_2nd_class) {
            setDisplayMode(FolderMode.FIRST_AND_SECOND_CLASS);
            return true;
        } else if (id == R.id.display_not_second_class) {
            setDisplayMode(FolderMode.NOT_SECOND_CLASS);
            return true;
        } else if (id == R.id.display_all) {
            setDisplayMode(FolderMode.ALL);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void onClickFolder(String folderServerId) {
        FolderSettings.actionSettings(this, account, folderServerId);
    }

    private void onCompact(Account account) {
        handler.workingAccount(R.string.compacting_account);
        MessagingController.getInstance(getApplication()).compact(account, null);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.folder_list_option, menu);
        refreshMenuItem = menu.findItem(R.id.list_folders);
        configureFolderSearchView(menu);
        return true;
    }

    private void configureFolderSearchView(Menu menu) {
        final MenuItem folderMenuItem = menu.findItem(R.id.filter_folders);
        final SearchView folderSearchView = (SearchView) folderMenuItem.getActionView();
        folderSearchView.setQueryHint(getString(R.string.folder_list_filter_hint));
        folderSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {

            @Override
            public boolean onQueryTextSubmit(String query) {
                folderMenuItem.collapseActionView();
                actionBar.setTitle(R.string.filter_folders_action);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                adapter.getFilter().filter(newText);
                return true;
            }
        });

        folderSearchView.setOnCloseListener(new SearchView.OnCloseListener() {

            @Override
            public boolean onClose() {
                actionBar.setTitle(R.string.folders_title);
                return false;
            }
        });
    }

    class FolderListAdapter extends BaseAdapter implements Filterable {
        private List<FolderInfoHolder> mFolders = new ArrayList<>();
        private List<FolderInfoHolder> mFilteredFolders = Collections.unmodifiableList(mFolders);
        private Filter mFilter = new FolderListFilter();

        public Object getItem(long position) {
            return getItem((int)position);
        }

        public Object getItem(int position) {
            return mFilteredFolders.get(position);
        }

        public long getItemId(int position) {
            return mFilteredFolders.get(position).folder.getServerId().hashCode() ;
        }

        public int getCount() {
            return mFilteredFolders.size();
        }

        @Override
        public boolean isEnabled(int item) {
            return true;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return true;
        }

        private ActivityListener mListener = new ActivityListener() {
            @Override
            public void informUserOfStatus() {
                handler.dataChanged();
            }

            @Override
            public void listFoldersStarted(Account account) {
                if (account.equals(ManageFoldersActivity.this.account)) {
                    handler.progress(true);
                }
                super.listFoldersStarted(account);

            }

            @Override
            public void listFoldersFailed(Account account, String message) {
                if (account.equals(ManageFoldersActivity.this.account)) {
                    handler.progress(false);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context, R.string.fetching_folders_failed, Toast.LENGTH_SHORT).show();

                        }
                    });
                }
                super.listFoldersFailed(account, message);
            }

            @Override
            public void listFoldersFinished(Account account) {
                if (account.equals(ManageFoldersActivity.this.account)) {

                    handler.progress(false);
                    MessagingController.getInstance(getApplication()).refreshListener(adapter.mListener);
                    handler.dataChanged();
                }
                super.listFoldersFinished(account);

            }

            @Override
            public void listFolders(Account account, List<LocalFolder> folders) {
                if (account.equals(ManageFoldersActivity.this.account)) {

                    List<FolderInfoHolder> newFolders = new LinkedList<>();
                    List<FolderInfoHolder> topFolders = new LinkedList<>();

                    Account.FolderMode aMode = account.getFolderDisplayMode();
                    for (LocalFolder folder : folders) {
                        Folder.FolderClass fMode = folder.getDisplayClass();

                        if ((aMode == FolderMode.FIRST_CLASS && fMode != Folder.FolderClass.FIRST_CLASS)
                                || (aMode == FolderMode.FIRST_AND_SECOND_CLASS &&
                                    fMode != Folder.FolderClass.FIRST_CLASS &&
                                    fMode != Folder.FolderClass.SECOND_CLASS)
                        || (aMode == FolderMode.NOT_SECOND_CLASS && fMode == Folder.FolderClass.SECOND_CLASS)) {
                            continue;
                        }

                        FolderInfoHolder holder = null;

                        int folderIndex = getFolderIndex(folder.getServerId());
                        if (folderIndex >= 0) {
                            holder = (FolderInfoHolder) getItem(folderIndex);
                        }

                        if (holder == null) {
                            holder = new FolderInfoHolder(context, folder, ManageFoldersActivity.this.account);
                        } else {
                            holder.populate(context, folder, ManageFoldersActivity.this.account);

                        }
                        if (folder.isInTopGroup()) {
                            topFolders.add(holder);
                        } else {
                            newFolders.add(holder);
                        }
                    }
                    Collections.sort(newFolders);
                    Collections.sort(topFolders);
                    topFolders.addAll(newFolders);
                    handler.newFolders(topFolders);
                }
                super.listFolders(account, folders);
            }

            @Override
            public void synchronizeMailboxStarted(Account account, String folderServerId, String folderName) {
                super.synchronizeMailboxStarted(account, folderServerId, folderName);
                if (account.equals(ManageFoldersActivity.this.account)) {

                    handler.progress(true);
                    handler.dataChanged();
                }

            }

            @Override
            public void synchronizeMailboxFinished(Account account, String folderServerId, int totalMessagesInMailbox, int numNewMessages) {
                super.synchronizeMailboxFinished(account, folderServerId, totalMessagesInMailbox, numNewMessages);
                if (account.equals(ManageFoldersActivity.this.account)) {
                    handler.progress(false);
                    refreshFolder(account, folderServerId);
                }
            }

            private void refreshFolder(Account account, String folderServerId) {
                // There has to be a cheaper way to get at the localFolder object than this
                LocalFolder localFolder = null;
                try {
                    if (account != null && folderServerId != null) {
                        if (!account.isAvailable(ManageFoldersActivity.this)) {
                            Timber.i("not refreshing folder of unavailable account");
                            return;
                        }
                        localFolder = DI.get(LocalStoreProvider.class).getInstance(account).getFolder(folderServerId);
                        FolderInfoHolder folderHolder = getFolder(folderServerId);
                        if (folderHolder != null) {
                            folderHolder.populate(context, localFolder, ManageFoldersActivity.this.account);
                            handler.dataChanged();
                        }
                    }
                } catch (Exception e) {
                    Timber.e(e, "Exception while populating folder");
                } finally {
                    if (localFolder != null) {
                        localFolder.close();
                    }
                }

            }

            @Override
            public void synchronizeMailboxFailed(Account account, String folderServerId, String message) {
                super.synchronizeMailboxFailed(account, folderServerId, message);
                if (!account.equals(ManageFoldersActivity.this.account)) {
                    return;
                }
                handler.progress(false);
                handler.dataChanged();
            }

            @Override
            public void messageDeleted(Account account, String folderServerId, String messageServerId) {
                synchronizeMailboxRemovedMessage(account, folderServerId, messageServerId);
            }

            @Override
            public void emptyTrashCompleted(Account account) {
                if (account.equals(ManageFoldersActivity.this.account)) {
                    refreshFolder(account, ManageFoldersActivity.this.account.getTrashFolder());
                }
            }

            @Override
            public void folderStatusChanged(Account account, String folderServerId, int unreadMessageCount) {
                if (account.equals(ManageFoldersActivity.this.account)) {
                    refreshFolder(account, folderServerId);
                    informUserOfStatus();
                }
            }

            @Override
            public void sendPendingMessagesCompleted(Account account) {
                super.sendPendingMessagesCompleted(account);
                if (account.equals(ManageFoldersActivity.this.account)) {
                    refreshFolder(account, ManageFoldersActivity.this.account.getOutboxFolder());
                }
            }

            @Override
            public void sendPendingMessagesStarted(Account account) {
                super.sendPendingMessagesStarted(account);

                if (account.equals(ManageFoldersActivity.this.account)) {
                    handler.dataChanged();
                }
            }

            @Override
            public void sendPendingMessagesFailed(Account account) {
                super.sendPendingMessagesFailed(account);
                if (account.equals(ManageFoldersActivity.this.account)) {
                    refreshFolder(account, ManageFoldersActivity.this.account.getOutboxFolder());
                }
            }
        };


        int getFolderIndex(String folder) {
            FolderInfoHolder searchHolder = new FolderInfoHolder();
            searchHolder.serverId = folder;
            return   mFilteredFolders.indexOf(searchHolder);
        }

        FolderInfoHolder getFolder(String folder) {
            int index = getFolderIndex(folder);
            if (index >= 0) {
                FolderInfoHolder holder = (FolderInfoHolder) getItem(index);
                if (holder != null) {
                    return holder;
                }
            }
            return null;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            if (position <= getCount()) {
                return  getItemView(position, convertView, parent);
            } else {
                Timber.e("getView with illegal position=%d called! count is only %d", position, getCount());
                return null;
            }
        }

        View getItemView(int itemPosition, View convertView, ViewGroup parent) {
            FolderInfoHolder folder = (FolderInfoHolder) getItem(itemPosition);
            View view;
            if (convertView != null) {
                view = convertView;
            } else {
                view = inflater.inflate(R.layout.folder_list_item, parent, false);
            }

            FolderViewHolder holder = (FolderViewHolder) view.getTag();

            if (holder == null) {
                holder = new FolderViewHolder();
                holder.folderName = view.findViewById(R.id.folder_name);

                holder.folderStatus = view.findViewById(R.id.folder_status);
                holder.folderListItemLayout = view.findViewById(R.id.folder_list_item_layout);
                holder.folderServerId = folder.serverId;

                view.setTag(holder);
            }

            if (folder == null) {
                return view;
            }

            holder.folderName.setText(folder.displayName);
            if (folder.status != null) {
                holder.folderStatus.setText(folder.status);
                holder.folderStatus.setVisibility(View.VISIBLE);
            } else {
                holder.folderStatus.setVisibility(View.GONE);
            }

            fontSizes.setViewTextSize(holder.folderName, fontSizes.getFolderName());

            if (K9.wrapFolderNames()) {
                holder.folderName.setEllipsize(null);
                holder.folderName.setSingleLine(false);
            }
            else {
                holder.folderName.setEllipsize(TruncateAt.START);
                holder.folderName.setSingleLine(true);
            }
            fontSizes.setViewTextSize(holder.folderStatus, fontSizes.getFolderStatus());

            return view;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        public Filter getFilter() {
            return mFilter;
        }

        public class FolderListFilter extends Filter {
            @Override
            protected FilterResults performFiltering(CharSequence searchTerm) {
                FilterResults results = new FilterResults();

                Locale locale = Locale.getDefault();
                if ((searchTerm == null) || (searchTerm.length() == 0)) {
                    List<FolderInfoHolder> list = new ArrayList<>(mFolders);
                    results.values = list;
                    results.count = list.size();
                } else {
                    final String searchTermString = searchTerm.toString().toLowerCase(locale);
                    final String[] words = searchTermString.split(" ");

                    final List<FolderInfoHolder> newValues = new ArrayList<>();

                    for (final FolderInfoHolder value : mFolders) {
                        if (value.displayName == null) {
                            continue;
                        }
                        final String valueText = value.displayName.toLowerCase(locale);

                        for (String word : words) {
                            if (valueText.contains(word)) {
                                newValues.add(value);
                                break;
                            }
                        }
                    }

                    results.values = newValues;
                    results.count = newValues.size();
                }

                return results;
            }

            @SuppressWarnings("unchecked")
            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                //noinspection unchecked
                mFilteredFolders = Collections.unmodifiableList((ArrayList<FolderInfoHolder>) results.values);
                // Send notification that the data set changed now
                notifyDataSetChanged();
            }
        }
    }

    static class FolderViewHolder {
        TextView folderName;
        TextView folderStatus;

        String folderServerId;
        LinearLayout folderListItemLayout;
    }
}
