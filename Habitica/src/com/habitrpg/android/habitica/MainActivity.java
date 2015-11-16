package com.habitrpg.android.habitica;

import android.content.DialogInterface;
import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.github.clans.fab.FloatingActionMenu;
import com.habitrpg.android.habitica.callbacks.HabitRPGUserCallback;
import com.habitrpg.android.habitica.callbacks.TaskCreationCallback;
import com.habitrpg.android.habitica.callbacks.TaskScoringCallback;
import com.habitrpg.android.habitica.callbacks.TaskUpdateCallback;
import com.habitrpg.android.habitica.databinding.ValueBarBinding;
import com.habitrpg.android.habitica.events.commands.BuyRewardCommand;
import com.habitrpg.android.habitica.events.HabitScoreEvent;
import com.habitrpg.android.habitica.events.commands.TaskCheckedCommand;
import com.habitrpg.android.habitica.events.TaskLongPressedEvent;
import com.habitrpg.android.habitica.events.TaskSaveEvent;
import com.habitrpg.android.habitica.events.TaskTappedEvent;
import com.habitrpg.android.habitica.events.ToggledInnStateEvent;
import com.habitrpg.android.habitica.events.commands.AddNewTaskCommand;
import com.habitrpg.android.habitica.events.commands.CreateTagCommand;
import com.habitrpg.android.habitica.events.commands.FilterTasksByTagsCommand;
import com.habitrpg.android.habitica.helpers.TagsHelper;
import com.habitrpg.android.habitica.prefs.PrefsActivity;
import com.habitrpg.android.habitica.ui.AvatarWithBarsViewModel;
import com.habitrpg.android.habitica.ui.EditTextDrawer;
import com.habitrpg.android.habitica.ui.MainDrawerBuilder;
import com.habitrpg.android.habitica.ui.adapter.HabitItemRecyclerViewAdapter;
import com.habitrpg.android.habitica.ui.adapter.IReceiveNewEntries;
import com.habitrpg.android.habitica.ui.fragments.TaskRecyclerViewFragment;
import com.habitrpg.android.habitica.ui.helpers.Debounce;
import com.habitrpg.android.habitica.userpicture.UserPicture;
import com.magicmicky.habitrpgwrapper.lib.models.HabitRPGUser;
import com.magicmicky.habitrpgwrapper.lib.models.SuppressedModals;
import com.magicmicky.habitrpgwrapper.lib.models.Tag;
import com.magicmicky.habitrpgwrapper.lib.models.TaskDirection;
import com.magicmicky.habitrpgwrapper.lib.models.TaskDirectionData;
import com.magicmicky.habitrpgwrapper.lib.models.tasks.ItemData;
import com.magicmicky.habitrpgwrapper.lib.models.tasks.Task;
import com.magicmicky.habitrpgwrapper.lib.models.tasks.TaskTag;
import com.mikepenz.materialdrawer.Drawer;
import com.mikepenz.materialdrawer.DrawerBuilder;
import com.mikepenz.materialdrawer.model.SectionDrawerItem;
import com.mikepenz.materialdrawer.model.SwitchDrawerItem;
import com.mikepenz.materialdrawer.model.interfaces.IDrawerItem;
import com.mikepenz.materialdrawer.interfaces.OnCheckedChangeListener;
import com.raizlabs.android.dbflow.runtime.FlowContentObserver;
import com.raizlabs.android.dbflow.runtime.transaction.BaseTransaction;
import com.raizlabs.android.dbflow.runtime.transaction.TransactionListener;
import com.raizlabs.android.dbflow.sql.builder.Condition;
import com.raizlabs.android.dbflow.sql.language.Select;
import com.raizlabs.android.dbflow.structure.BaseModel;
import com.raizlabs.android.dbflow.structure.Model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import butterknife.InjectView;
import butterknife.OnClick;
import de.greenrobot.event.EventBus;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;



public class MainActivity extends AvatarActivityBase implements HabitRPGUserCallback.OnUserReceived,
        TaskScoringCallback.OnTaskScored, FlowContentObserver.OnSpecificModelStateChangedListener,
        OnCheckedChangeListener {

    public enum SnackbarDisplayType {
        NORMAL, FAILURE, DROP
    }

    static final int TASK_CREATED_RESULT = 1;
    static final int TASK_UPDATED_RESULT = 2;

    Drawer filterDrawer;

    Map<Integer, TaskRecyclerViewFragment> ViewFragmentsDictionary = new HashMap<>();

    APIHelper mAPIHelper;

    @InjectView(R.id.fab_menu)
    FloatingActionMenu floatingMenu;

    FlowContentObserver observer;

    private TagsHelper tagsHelper;

    private ContentCache contentCache;

    private MaterialDialog faintDialog;

    @Override
    protected int getLayoutRes() {
        return R.layout.activity_main;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.hostConfig = PrefsActivity.fromContext(this);
        if (hostConfig == null || hostConfig.getApi() == null || hostConfig.getApi().equals("") || hostConfig.getUser() == null || hostConfig.getUser().equals("")) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        drawer = MainDrawerBuilder.CreateDefaultBuilderSettings(this, toolbar)
                .build();

        filterDrawer = new DrawerBuilder()
                .withActivity(this)
                .withDrawerGravity(Gravity.RIGHT)
                .withCloseOnClick(false)
                .append(drawer);

        viewPager.setCurrentItem(0);

        new Select().from(HabitRPGUser.class).where(Condition.column("id").eq(hostConfig.getUser())).async().querySingle(userTransactionListener);
        this.observer = new FlowContentObserver();
        this.observer.registerForContentChanges(this.getApplicationContext(), HabitRPGUser.class);

        this.observer.addSpecificModelChangeListener(this);

        this.tagsHelper = new TagsHelper();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mAPIHelper == null) {
            this.mAPIHelper = new APIHelper(this, hostConfig);

            mAPIHelper.retrieveUser(new HabitRPGUserCallback(this));

            contentCache = new ContentCache(mAPIHelper.apiService);
        }
        SetUserData();
    }

    @Override
    protected void onDestroy() {
        if (observer != null) {
            this.observer.unregisterForContentChanges(this.getApplicationContext());
        }
        EventBus.getDefault().unregister(this);

        super.onDestroy();
    }

    // region onClick for the FAB Menu

    @OnClick(R.id.fab_new_habit)
    public void onNewHabit(View view) {
        openNewTaskActivity("habit");
    }

    @OnClick(R.id.fab_new_daily)
    public void onNewDaily(View view) {
        openNewTaskActivity("daily");
    }

    @OnClick(R.id.fab_new_todo)
    public void onNewTodo(View view) {
        openNewTaskActivity("todo");
    }

    @OnClick(R.id.fab_new_reward)
    public void onNewReward(View view) {
        openNewTaskActivity("reward");
    }


    private void openNewTaskActivity(String type) {
        Bundle bundle = new Bundle();
        bundle.putString("type", type);
        bundle.putStringArrayList("tagsId", new ArrayList<String>(this.tagsHelper.getTags()));

        Intent intent = new Intent(this, TaskFormActivity.class);
        intent.putExtras(bundle);
        intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        startActivityForResult(intent, TASK_CREATED_RESULT);
    }

    // endregion

    private void showSnackbar(String content) {
        showSnackbar(content, SnackbarDisplayType.NORMAL);
    }

    private void showSnackbar(String content, SnackbarDisplayType displayType) {
        Snackbar snackbar = Snackbar.make(floatingMenu, content, Snackbar.LENGTH_LONG);
        View snackbarView = snackbar.getView();

        if (displayType == SnackbarDisplayType.FAILURE) {

            //change Snackbar's background color;
            snackbarView.setBackgroundColor(ContextCompat.getColor(this, R.color.worse_10));
        } else if (displayType == SnackbarDisplayType.DROP) {
            TextView tv = (TextView) snackbarView.findViewById(android.support.design.R.id.snackbar_text);
            tv.setMaxLines(5);
            snackbarView.setBackgroundColor(ContextCompat.getColor(this, R.color.best_10));
        }
        snackbar.show();
    }

    //region Events

    public void onEvent(CreateTagCommand event) {
        Tag t = new Tag();
        t.setName(event.tagName);
        t.save();

        mAPIHelper.apiService.createTag(t, new Callback<List<Tag>>() {
            @Override
            public void success(List<Tag> tags, Response response) {
                FillTagFilterDrawer(tags);
            }

            @Override
            public void failure(RetrofitError error) {
                showSnackbar("Error: " + error.getMessage(), SnackbarDisplayType.FAILURE);
            }
        });
    }

    public void onEvent(TaskTappedEvent event) {
        if (event.Task.type.equals("reward"))
            return;

        Bundle bundle = new Bundle();
        bundle.putString("type", event.Task.getType());
        bundle.putString("taskId", event.Task.getId());
        bundle.putStringArrayList("tagsId", new ArrayList<String>(this.tagsHelper.getTags()));
        Intent intent = new Intent(this, TaskFormActivity.class);
        intent.putExtras(bundle);
        startActivityForResult(intent, TASK_UPDATED_RESULT);
    }

    public void onEvent(TaskLongPressedEvent event) {
    }

    public void onEvent(TaskCheckedCommand event) {
        mAPIHelper.updateTaskDirection(event.Task.getId(), event.Task.getCompleted() ? TaskDirection.down : TaskDirection.up, new TaskScoringCallback(this, event.Task.getId()));
    }

    public void onEvent(HabitScoreEvent event) {
        mAPIHelper.updateTaskDirection(event.Habit.getId(), event.Up ? TaskDirection.up : TaskDirection.down, new TaskScoringCallback(this, event.Habit.getId()));
    }

    public void onEvent(AddNewTaskCommand event) {
        openNewTaskActivity(event.ClassType.toLowerCase());
    }

    public void onEvent(final BuyRewardCommand event) {
        final String rewardKey = event.Reward.getId();

        if (User.getStats().getGp() < event.Reward.getValue()) {
            showSnackbar("Not enough Gold", SnackbarDisplayType.FAILURE);
            return;
        }

        if (event.Reward.specialTag == "item") {
            if (rewardKey.equals("potion")) {
                int currentHp = User.getStats().getHp().intValue();
                int maxHp = User.getStats().getMaxHealth();

                if(currentHp == maxHp) {
                    showSnackbar("You don't need to buy an health potion", SnackbarDisplayType.FAILURE);
                    return;
                }
            }

            mAPIHelper.apiService.buyItem(event.Reward.getId(), new Callback<Void>() {

                @Override
                public void success(Void aVoid, Response response) {
                    switch (rewardKey) {
                        case "potion":
                            double newHp = Math.min(User.getStats().getMaxHealth(), User.getStats().getHp() + 15);
                            User.getStats().setHp(newHp);

                            updateHeader();

                            break;
                        default:

                            // TODO Add bought item to the avatar

                            break;
                    }

                    showSnackbar("Buy Reward Successful " + event.Reward.getText());
                }

                @Override
                public void failure(RetrofitError error) {
                    showSnackbar("Buy Reward Error " + event.Reward.getText(), SnackbarDisplayType.FAILURE);
                }
            });
        } else {
            // User created Rewards

            mAPIHelper.updateTaskDirection(rewardKey, TaskDirection.down, new TaskScoringCallback(this, rewardKey));
        }
    }

    public void onEvent(final TaskSaveEvent event) {
        Task task = (Task) event.task;
        Log.d("tags", "Task saving");
        if (event.created) {
            this.mAPIHelper.createNewTask(task, new TaskCreationCallback());
            updateTags(event.task.getTags());
        } else {
            this.mAPIHelper.updateTask(task, new TaskUpdateCallback());
        }
    }

    public void onEvent(ToggledInnStateEvent event) {
        User.getPreferences().setSleep(event.Inn);

        updateUserAvatars();
    }

    //endregion Events

    private void notifyUser(double xp, double hp, double gold,
                            int lvl, double delta) {
        StringBuilder message = new StringBuilder();
        SnackbarDisplayType displayType = SnackbarDisplayType.NORMAL;
        if (lvl > User.getStats().getLvl()) {
            displayLevelUpDialog(lvl);

            this.mAPIHelper.retrieveUser(new HabitRPGUserCallback(this));
            User.getStats().setLvl((int) lvl);
            showSnackbar(message.toString());
        } else {
            com.magicmicky.habitrpgwrapper.lib.models.Stats stats = User.getStats();

            if (xp > stats.getExp()) {
                message.append(" + ").append(round(xp - stats.getExp(), 2)).append(" XP");
                User.getStats().setExp(xp);
            }
            if (hp != stats.getHp()) {
                displayType = SnackbarDisplayType.FAILURE;
                message.append(" - ").append(round(stats.getHp() - hp, 2)).append(" HP");
                User.getStats().setHp(hp);
            }
            if (gold > stats.getGp()) {
                message.append(" + ").append(round(gold - stats.getGp(), 2)).append(" GP");
                stats.setGp(gold);
            } else if (gold < stats.getGp()) {
                displayType = SnackbarDisplayType.FAILURE;
                message.append(" - ").append(round(stats.getGp() - gold, 2)).append(" GP");
                stats.setGp(gold);
            }
            showSnackbar(message.toString(), displayType);

            updateUserAvatars();
        }
    }

    static public Double round(Double value, int n) {
        return (Math.round(value * Math.pow(10, n))) / (Math.pow(10, n));
    }

    public void loadTaskLists() {
        android.support.v4.app.FragmentManager fragmentManager = getSupportFragmentManager();

        viewPager.setAdapter(new FragmentPagerAdapter(fragmentManager) {

            int oldPosition = -1;

            @Override
            public Fragment getItem(int position) {
                int layoutOfType;
                TaskRecyclerViewFragment fragment;
                HabitItemRecyclerViewAdapter adapter;

                switch (position) {
                    case 0:
                        layoutOfType = R.layout.habit_item_card;
                        fragment = TaskRecyclerViewFragment.newInstance(new HabitItemRecyclerViewAdapter(Task.TYPE_HABIT, MainActivity.this.tagsHelper, layoutOfType, HabitItemRecyclerViewAdapter.HabitViewHolder.class, MainActivity.this), Task.TYPE_HABIT);

                        break;
                    case 1:
                        layoutOfType = R.layout.daily_item_card;
                        adapter = new HabitItemRecyclerViewAdapter(Task.TYPE_DAILY, MainActivity.this.tagsHelper, layoutOfType, HabitItemRecyclerViewAdapter.DailyViewHolder.class, MainActivity.this);
                        if (User != null) {
                            adapter.dailyResetOffset = User.getPreferences().getDayStart();
                        }
                        fragment = TaskRecyclerViewFragment.newInstance(adapter, Task.TYPE_DAILY);
                        break;
                    case 3:
                        layoutOfType = R.layout.reward_item_card;
                        adapter = new HabitItemRecyclerViewAdapter(Task.TYPE_REWARD, MainActivity.this.tagsHelper,
                                layoutOfType, HabitItemRecyclerViewAdapter.RewardViewHolder.class, MainActivity.this,
                                new HabitItemRecyclerViewAdapter.IAdditionalEntries() {
                                    @Override
                                    public void GetAdditionalEntries(final IReceiveNewEntries callBack) {

                                        // request buyable gear
                                        mAPIHelper.apiService.getInventoryBuyableGear(new Callback<List<ItemData>>() {
                                            @Override
                                            public void success(List<ItemData> itemDatas, Response response) {

                                                // get itemdata list
                                                ArrayList<String> itemKeys = new ArrayList<String>();
                                                for (ItemData item : itemDatas) {
                                                    itemKeys.add(item.key);
                                                }
                                                itemKeys.add("potion");

                                                contentCache.GetItemDataList(itemKeys, new ContentCache.GotContentEntryCallback<List<ItemData>>() {
                                                    @Override
                                                    public void GotObject(List<ItemData> obj) {
                                                        ArrayList<Task> buyableItems = new ArrayList<Task>();

                                                        for (ItemData item : obj) {
                                                            Task reward = new Task();
                                                            reward.text = item.text;
                                                            reward.notes = item.notes;
                                                            reward.value = item.value;
                                                            reward.setType("reward");
                                                            reward.specialTag = "item";
                                                            reward.setId(item.key);

                                                            buyableItems.add(reward);
                                                        }

                                                        callBack.GotAdditionalItems(buyableItems);

                                                    }
                                                });


                                            }

                                            @Override
                                            public void failure(RetrofitError error) {

                                            }
                                        });

                                    }
                                });


                        fragment = TaskRecyclerViewFragment.newInstance(adapter, Task.TYPE_REWARD);
                        break;
                    default:
                        layoutOfType = R.layout.todo_item_card;
                        fragment = TaskRecyclerViewFragment.newInstance(new HabitItemRecyclerViewAdapter(Task.TYPE_TODO, MainActivity.this.tagsHelper, layoutOfType, HabitItemRecyclerViewAdapter.TodoViewHolder.class, MainActivity.this), Task.TYPE_TODO);
                }

                ViewFragmentsDictionary.put(position, fragment);

                return fragment;
            }

            @Override
            public int getCount() {
                return 4;
            }

            @Override
            public CharSequence getPageTitle(int position) {
                switch (position) {
                    case 0:
                        return "Habits";
                    case 1:
                        return "Dailies";
                    case 2:
                        return "Todos";
                    case 3:
                        return "Rewards";
                }
                return "";
            }
        });


        detail_tabs.setupWithViewPager(viewPager);

    }


    public int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main_activity, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        switch (id) {
            case R.id.action_search:
                filterDrawer.openDrawer();
                return true;
            case R.id.action_reload:
                mAPIHelper.retrieveUser(new HabitRPGUserCallback(this));
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void updateUserAvatars() {
        avatarInHeader.updateData(User);
    }

    private void updateHeader() {
        updateUserAvatars();
        setTitle(User.getProfile().getName());

        android.support.v7.app.ActionBarDrawerToggle actionBarDrawerToggle = drawer.getActionBarDrawerToggle();

        if (actionBarDrawerToggle != null) {
            actionBarDrawerToggle.setDrawerIndicatorEnabled(true);
        }
    }

    @Override
    public void onUserReceived(HabitRPGUser user) {
    }

    @Override
    public void onUserFail() {

    }

    @Override
    public void onTaskDataReceived(TaskDirectionData data) {
        notifyUser(data.getExp(), data.getHp(), data.getGp(), data.getLvl(), data.getDelta());
        if (data.get_tmp() != null) {
            if (data.get_tmp().getDrop() != null) {
                showSnackbar(data.get_tmp().getDrop().getDialog(), SnackbarDisplayType.DROP);
            }
        }

    }

    @Override
    public void onTaskScoringFailed() {

    }

    @Override
    public void onModelStateChanged(Class<? extends Model> aClass, BaseModel.Action action, String s, String s1) {
        if(aClass != HabitRPGUser.class)
            return;


        new Select().from(HabitRPGUser.class).where(Condition.column("id").eq(hostConfig.getUser())).async().querySingle(userTransactionListener);
        Log.d("db", "received notif");
//        SetUserData();
    }

    private boolean taskListAlreadyAdded;

    private void SetUserData() {
        if (User != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!taskListAlreadyAdded) {
                        taskListAlreadyAdded = true;
                        loadTaskLists();
                        FillTagFilterDrawer(User.getTags());
                    }
                    TaskRecyclerViewFragment fragment = ViewFragmentsDictionary.get(2);
                    if (fragment != null) {
                        HabitItemRecyclerViewAdapter adapter =(HabitItemRecyclerViewAdapter)fragment.mAdapter;
                        adapter.dailyResetOffset = User.getPreferences().getDayStart();
                    }
                    updateHeader();

                    displayDeathDialogIfNeeded();
                }
            });
        }
    }

    // Filter Tags

    public void FillTagFilterDrawer(List<Tag> tagList) {
        filterDrawer.removeAllItems();
        filterDrawer.addItems(
                new SectionDrawerItem().withName("Filter by Tag"),
                new EditTextDrawer()
        );

        for (Tag t : tagList) {
            filterDrawer.addItem(new SwitchDrawerItem()
                            .withName(t.getName())
                            .withTag(t)
                            .withDescription("" + t.getTasks().size())
                            .withOnCheckedChangeListener(this)
            );
        }
    }

    /*
        Updates concerned tags.
     */
    public void updateTags(List<TaskTag> tags) {
        Log.d("tags", "Updating tags");
        List<IDrawerItem> filters = filterDrawer.getDrawerItems();
        for (IDrawerItem filter : filters) {
            if (filter instanceof SwitchDrawerItem) {
                SwitchDrawerItem currentfilter = (SwitchDrawerItem) filter;
                Log.v("tags", "Tag " + currentfilter.getName());
                String tagId = ((Tag) currentfilter.getTag()).getId();
                for (TaskTag tag : tags) {
                    Tag currentTag = tag.getTag();


                    if (tagId != null && currentTag != null && tagId.equals(currentTag.getId())) {
                        currentfilter.withDescription("" + (currentTag.getTasks().size() + 1));
                        filterDrawer.updateItem(currentfilter);
                    }
                }
            }
        }

    }

    // A Filter was checked

    private Debounce filterChangedHandler = new Debounce(1500, 1000) {
        @Override
        public void execute() {
            ArrayList<String> tagList = new ArrayList<>();

            for (Map.Entry<String, Boolean> f : tagFilterMap.entrySet()) {
                if (f.getValue()) {
                    tagList.add(f.getKey());
                }
            }
            tagsHelper.setTags(tagList);
            EventBus.getDefault().post(new FilterTasksByTagsCommand());
        }
    };


    private HashMap<String, Boolean> tagFilterMap = new HashMap<>();

    @Override
    public void onCheckedChanged(IDrawerItem iDrawerItem, CompoundButton compoundButton, boolean b) {
        Tag t = (Tag) iDrawerItem.getTag();
        Log.w("Tags", "onCheckedChanged" + compoundButton.isChecked() + " : " + b);
        if (t != null) {
            tagFilterMap.put(t.getId(), b);
            filterChangedHandler.hit();
            showSnackbar(t.getName() + " : " + b);
        }
    }

    private TransactionListener<HabitRPGUser> userTransactionListener = new TransactionListener<HabitRPGUser>() {
        @Override
        public void onResultReceived(HabitRPGUser habitRPGUser) {
            User = habitRPGUser;
            SetUserData();
        }

        @Override
        public boolean onReady(BaseTransaction<HabitRPGUser> baseTransaction) {
            return true;
        }

        @Override
        public boolean hasResult(BaseTransaction<HabitRPGUser> baseTransaction, HabitRPGUser habitRPGUser) {
            return true;
        }
    };

    private void displayLevelUpDialog(int level) {
        SuppressedModals suppressedModals = User.getPreferences().getSuppressModals();
        if (suppressedModals != null) {
            if (suppressedModals.getLevelUp()) {
                return;
            }
        }

        MaterialDialog dialog = new MaterialDialog.Builder(this)
                .title(R.string.levelup_header)
                .customView(R.layout.levelup_dialog, true)
                .positiveText(R.string.levelup_button)
                .positiveColorRes(R.color.brand_100)
                .build();

        View customView = dialog.getCustomView();
        if (customView != null) {
            TextView detailView = (TextView)customView.findViewById(R.id.levelupDetail);
            detailView.setText(this.getString(R.string.levelup_detail, level));

            ImageView avatarView = (ImageView)customView.findViewById(R.id.avatarView);
            UserPicture userPicture = new UserPicture(User, this, false, false, false);
            userPicture.setPictureOn(avatarView);
        }

        dialog.show();
    }

    private void displayDeathDialogIfNeeded() {

        if (User.getStats().getHp() > 0) {
            return;
        }

        if (this.faintDialog == null) {
            final MainActivity activity = this;
            this.faintDialog = new MaterialDialog.Builder(this)
                    .title(R.string.faint_header)
                    .customView(R.layout.faint_dialog, true)
                    .positiveText(R.string.faint_button)
                    .positiveColorRes(R.color.worse_100)
                    .dismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            faintDialog = null;
                            mAPIHelper.reviveUser(new HabitRPGUserCallback(activity));
                        }
                    })
                    .cancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            faintDialog = null;
                        }
                    })
                    .build();

            View customView = this.faintDialog.getCustomView();
            if (customView != null) {
                View hpBarView = customView.findViewById(R.id.hpBar);

                ValueBarBinding hpBar = DataBindingUtil.bind(hpBarView);
                hpBar.setPartyMembers(true);
                AvatarWithBarsViewModel.setHpBarData(hpBar, User.getStats(), this);

                ImageView avatarView = (ImageView)customView.findViewById(R.id.avatarView);
                UserPicture userPicture = new UserPicture(User, this, false, false, false);
                userPicture.setPictureOn(avatarView);
            }

            this.faintDialog.show();
        }

    }
}