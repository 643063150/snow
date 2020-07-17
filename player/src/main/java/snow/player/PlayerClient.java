package snow.player;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.List;

import channel.helper.ChannelHelper;
import channel.helper.DispatcherUtil;
import channel.helper.pipe.CustomActionPipe;
import channel.helper.pipe.MessengerPipe;
import snow.player.media.MusicItem;
import snow.player.playlist.Playlist;
import snow.player.playlist.PlaylistManager;
import snow.player.playlist.PlaylistPlayer;
import snow.player.radio.RadioStation;
import snow.player.radio.RadioStationPlayer;
import snow.player.playlist.PlaylistState;
import snow.player.playlist.PlaylistStateListener;
import snow.player.radio.RadioStationState;
import snow.player.radio.RadioStationStateListener;

/**
 * 播放器客户端，用于向播放器发生各种控制命令。
 */
public class PlayerClient {
    private Context mApplicationContext;
    private Class<? extends PlayerService> mPlayerService;
    private String mToken;

    private MediaBrowserCompat mMediaBrowser;
    private MediaControllerCompat mMediaController;

    private PlayerConfig mPlayerConfig;

    private PlayerManager mPlayerManager;

    private PlaylistController mPlaylistController;
    private RadioStationController mRadioStationController;

    private PlayerManager.OnCommandCallback mCommandCallback;

    private List<PlayerManager.OnPlayerTypeChangeListener> mAllPlayerTypeChangeListener;

    private OnConnectCallback mConnectCallback;

    private PlayerClient(Context context, Class<? extends PlayerService> playerService) {
        mApplicationContext = context.getApplicationContext();
        mPlayerService = playerService;
        mToken = generateToken();

        mPlayerConfig = new PlayerConfig(context, mToken);
        mAllPlayerTypeChangeListener = new ArrayList<>();

        initMediaBrowser();
        initAllController();
        initConfigChangeListener();
    }

    /**
     * 创建一个 PlayerClient 对象。
     *
     * @param context       Context 对象，不能为 null
     * @param playerService PlayerService 或者其子类的 Class 对象，不能为 null
     * @return PlayerClient 对象
     */
    public static PlayerClient newInstance(@NonNull Context context,
                                           @NonNull Class<? extends PlayerService> playerService) {
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(playerService);

        if (serviceNotFound(context, playerService)) {
            throw new IllegalArgumentException("PlayerService not found, Please check your 'AndroidManifest.xml'");
        }

        return new PlayerClient(context, playerService);
    }

    private static boolean serviceNotFound(Context context, Class<? extends PlayerService> playerService) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(context, playerService);
        return pm.resolveService(intent, 0) == null;
    }

    private String generateToken() {
        return mPlayerService.getName();
    }

    private void initMediaBrowser() {
        mMediaBrowser = new MediaBrowserCompat(mApplicationContext,
                new ComponentName(mApplicationContext, mPlayerService),
                new MediaBrowserCompat.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        try {
                            mMediaController = new MediaControllerCompat(mApplicationContext, mMediaBrowser.getSessionToken());

                            PlayerClient.this.onConnected(mMediaController);

                            if (mConnectCallback != null) {
                                mConnectCallback.onConnected(true);
                            }
                        } catch (RemoteException e) {
                            mMediaBrowser.disconnect();
                            onConnectionFailed();
                        }
                    }

                    @Override
                    public void onConnectionFailed() {
                        onDisconnected();

                        if (mConnectCallback != null) {
                            mConnectCallback.onConnected(false);
                        }
                    }
                }, null);
    }

    private void initAllController() {
        mPlaylistController = new PlaylistController(mApplicationContext, mToken);
        mRadioStationController = new RadioStationController();
    }

    private void initConfigChangeListener() {
        mCommandCallback = new PlayerManager.OnCommandCallback() {
            @Override
            public void onShutdown() {
                disconnect();
            }

            @Override
            public void onPlayerTypeChanged(PlayerManager.PlayerType playerType) {
                notifyPlayerTypeChanged(playerType);
            }

            @Override
            public void syncPlayerState(PlayerManager.PlayerType playerType, PlaylistState playlistState, RadioStationState radioStationState) {
                notifyPlayerTypeChanged(playerType);

                mPlaylistController.setPlaylistState(playlistState);
                mRadioStationController.setRadioStationState(radioStationState);
            }
        };
    }

    private void notifyPlayerTypeChanged(PlayerManager.PlayerType playerType) {
        for (PlayerManager.OnPlayerTypeChangeListener listener : mAllPlayerTypeChangeListener) {
            listener.onPlayerTypeChanged(playerType);
        }
    }

    private void onConnected(MediaControllerCompat mediaController) {
        CustomActionPipe controllerPipe = new CustomActionPipe(mediaController.getTransportControls());

        mPlayerManager = ChannelHelper.newEmitter(PlayerManager.class, controllerPipe);

        mPlaylistController.setDelegate(ChannelHelper.newEmitter(PlaylistPlayer.class, controllerPipe));
        mRadioStationController.setDelegate(ChannelHelper.newEmitter(RadioStationPlayer.class, controllerPipe));

        mPlaylistController.setConnected(true);
        mRadioStationController.setConnected(true);

        MessengerPipe listenerPipe = new MessengerPipe(DispatcherUtil.merge(
                ChannelHelper.newDispatcher(PlayerManager.OnCommandCallback.class, mCommandCallback),
                ChannelHelper.newDispatcher(PlaylistStateListener.class, mPlaylistController.getPlaylistStateListener()),
                ChannelHelper.newDispatcher(RadioStationStateListener.class, mRadioStationController.getRadioStationStateListener())
        ));

        mPlayerManager.registerPlayerStateListener(mToken, listenerPipe.getBinder());
    }

    private void onDisconnected() {
        mPlaylistController.setConnected(false);
        mRadioStationController.setConnected(false);

        if (isConnected()) {
            mPlayerManager.unregisterPlayerStateListener(mToken);
        }
    }

    /**
     * 连接播放器。
     */
    public void connect() {
        if (isConnected()) {
            return;
        }

        mMediaBrowser.connect();
    }

    /**
     * 连接播放器
     */
    public void connect(OnConnectCallback callback) {
        if (isConnected()) {
            callback.onConnected(true);
            return;
        }

        mConnectCallback = callback;
        connect();
    }

    /**
     * 断开与播放器的连接。
     */
    public void disconnect() {
        if (!isConnected()) {
            return;
        }

        onDisconnected();
        mMediaBrowser.disconnect();
    }

    /**
     * 播放器释放已连接。
     *
     * @return 如果播放器已连接则返回 true，否则返回 false
     */
    public boolean isConnected() {
        return mMediaBrowser.isConnected();
    }

    /**
     * 获取 {@link MediaControllerCompat} 对象。
     *
     * @return {@link MediaControllerCompat} 对象，如果还没有建立连接（{@link #isConnected()} 返回
     * {@code false}），那么该方法可能会返回 null
     */
    @Nullable
    public MediaControllerCompat getMediaController() {
        return mMediaController;
    }

    /**
     * 设置播放器的首选音质（默认为 {@link Player.SoundQuality#STANDARD}）。
     * <p>
     * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
     *
     * @param soundQuality 要设置的音质，不能为 null
     * @see Player.SoundQuality#STANDARD
     * @see Player.SoundQuality#LOW
     * @see Player.SoundQuality#HIGH
     * @see Player.SoundQuality#SUPER
     * @see #getSoundQuality()
     */
    public void setSoundQuality(@NonNull Player.SoundQuality soundQuality) {
        Preconditions.checkNotNull(soundQuality);
        if (!isConnected()) {
            return;
        }

        mPlayerManager.setSoundQuality(soundQuality);
    }

    /**
     * 修改音频特效的配置。
     *
     * @param config 要设置的音频特效配置，不能为 null
     */
    public void setAudioEffectConfig(@NonNull Bundle config) {
        Preconditions.checkNotNull(config);
        if (!isConnected()) {
            return;
        }

        mPlayerManager.setAudioEffectConfig(config);
    }

    /**
     * 设置是否启用音频特效（如：均衡器）（默认为 false）。
     * <p>
     * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
     *
     * @param enabled 是否启用音频特效
     * @see #isAudioEffectEnabled()
     */
    public void setAudioEffectEnabled(boolean enabled) {
        if (!isConnected()) {
            return;
        }

        mPlayerManager.setAudioEffectEnabled(enabled);
    }

    /**
     * 设置是否只允许在 WiFi 网络下播放音乐（默认为 false）。
     * <p>
     * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
     *
     * @param onlyWifiNetwork 是否只允许在 WiFi 网络下播放音乐
     * @see #isOnlyWifiNetwork()
     */
    public void setOnlyWifiNetwork(boolean onlyWifiNetwork) {
        if (!isConnected()) {
            return;
        }

        mPlayerManager.setOnlyWifiNetwork(onlyWifiNetwork);
    }

    /**
     * 设置是否忽略音频焦点的丢失（默认为 false）。
     * <p>
     * 如果设为 true，即使音频焦点丢失，当前播放器依然会继续播放。简单的说，就是是否可以和其他应用同时播放音频。
     * <p>
     * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
     *
     * @param ignoreLossAudioFocus 是否忽略音频焦点的丢失
     * @see #isIgnoreLossAudioFocus()
     */
    public void setIgnoreLossAudioFocus(boolean ignoreLossAudioFocus) {
        if (!isConnected()) {
            return;
        }

        mPlayerManager.setIgnoreLossAudioFocus(ignoreLossAudioFocus);
    }

    /**
     * 获取当前播放器类型。
     * <p>
     * 共有两种播放器类型：
     * <ol>
     *     <li>{@link PlayerManager.PlayerType#PLAYLIST}：列表播放器</li>
     *     <li>{@link PlayerManager.PlayerType#RADIO_STATION}：电台播放器</li>
     * </ol>
     * <p>
     * 使用 {@link PlaylistController} 控制播放器时，播放器类型为自动切换为
     * {@link PlayerManager.PlayerType#PLAYLIST}；使用 {@link RadioStationController} 控制播放器时，
     * 播放器类型会自动切换为 {@link PlayerManager.PlayerType#RADIO_STATION}。
     * <p>
     * 默认的播放器类型为 {@link PlayerManager.PlayerType#PLAYLIST} 列表播放器。你可以注册一个
     * {@link PlayerManager.OnPlayerTypeChangeListener} 来监听播放器类型的改变，并根据不同的播放器类型调
     * 整 UI 外观。
     *
     * @return 当前播放器类型
     */
    public PlayerManager.PlayerType getPlayerType() {
        return mPlayerConfig.getPlayerType();
    }

    /**
     * 获取当前播放器的首选音质。
     *
     * @see #setSoundQuality(Player.SoundQuality)
     */
    public Player.SoundQuality getSoundQuality() {
        return mPlayerConfig.getSoundQuality();
    }

    /**
     * 获取音频特效的配置。
     * <p>
     * 为了确保返回正确的值，请在连接成功后再调用该方法。
     *
     * @return 音频特效的配置
     */
    @Nullable
    public Bundle getAudioEffectConfig() {
        return mPlayerConfig.getAudioEffectConfig();
    }

    /**
     * 是否已启用音频特效。
     *
     * @see #setAudioEffectEnabled(boolean)
     */
    public boolean isAudioEffectEnabled() {
        return mPlayerConfig.isAudioEffectEnabled();
    }

    /**
     * 是否只允许使用 Wifi 网络（默认为 false）。
     *
     * @see #setOnlyWifiNetwork(boolean)
     */
    public boolean isOnlyWifiNetwork() {
        return mPlayerConfig.isOnlyWifiNetwork();
    }

    /**
     * 是否忽略音频焦点的丢失。
     *
     * @see #setIgnoreLossAudioFocus(boolean)
     */
    public boolean isIgnoreLossAudioFocus() {
        return mPlayerConfig.isIgnoreLossAudioFocus();
    }

    public PlaylistController getPlaylistController() {
        return mPlaylistController;
    }

    public RadioStationController getRadioStationController() {
        return mRadioStationController;
    }

    /**
     * 关闭播放器。
     * <p>
     * 调用该方法后，后台的播放器会自动关闭，并断开所有客户端的连接。通常不建议客户端调用此方法，如果客户端需要
     * 断开与播放器的连接，注意 {@link #disconnect()} 方法即可。
     */
    public void shutdown() {
        if (isConnected()) {
            mPlayerManager.shutdown();
        }
    }

    /**
     * 添加播放器类型监听器。
     *
     * @param listener 播放器类型监听器，该监听器会在播放器类型改变时被调用
     */
    public void addOnPlayerTypeChangeListener(PlayerManager.OnPlayerTypeChangeListener listener) {
        if (mAllPlayerTypeChangeListener.contains(listener)) {
            return;
        }

        mAllPlayerTypeChangeListener.add(listener);
    }

    /**
     * 移除播放器类型监听器。
     *
     * @param listener 要移除的监听器。
     */
    public void removeOnPlayerTypeChangeListener(PlayerManager.OnPlayerTypeChangeListener listener) {
        mAllPlayerTypeChangeListener.remove(listener);
    }

    public static class PlaylistController implements PlaylistPlayer {
        private PlaylistManagerImp mPlaylistManager;
        private PlaylistPlayer mDelegate;
        private PlaylistStateHolder mPlaylistStateHolder;
        private boolean mConnected;

        PlaylistController(Context context, String playlistId) {
            mPlaylistManager = new PlaylistManagerImp(context, playlistId);
            mPlaylistStateHolder = new PlaylistStateHolder(mPlaylistManager);
            mConnected = false;

            mPlaylistManager.setOnModifyPlaylistListener(this);
        }

        void setDelegate(PlaylistPlayer delegate) {
            mDelegate = delegate;
        }

        void setConnected(boolean connected) {
            mConnected = connected;
            mPlaylistManager.setEditable(connected);
        }

        void setPlaylistState(PlaylistState playlistState) {
            mPlaylistStateHolder.setPlaylistState(playlistState);
        }

        PlaylistStateListener getPlaylistStateListener() {
            return mPlaylistStateHolder;
        }

        /**
         * 是否已连接到播放器。
         *
         * @return 如果播放器已连接，则返回 true，否则返回 false
         */
        public boolean isConnected() {
            return mConnected;
        }

        /**
         * 获取当前 PlayerController 的 PlaylistManager 对象。
         * <p>
         * 注意！只允许在播放器已连接（{@link #isConnected()} 返回 true）时，才允许使用 PlaylistManager
         * 修改播放队列。当然，即使未连接，使用 PlaylistManager 访问播放队列还是允许的。
         *
         * @return 当前 PlayerController 的 PlaylistManager 对象。
         */
        public PlaylistManager getPlaylistManager() {
            return mPlaylistManager;
        }

        /**
         * 设置一个新的播放列表。
         *
         * @param playlist 播放列表（不能为 null）
         */
        public void setPlaylist(@NonNull Playlist playlist) {
            setPlaylist(playlist, 0, false);
        }

        /**
         * 设置一个新的播放列表。
         *
         * @param playlist 播放列表（不能为 null）
         * @param play     是否立即播放列表中的音乐
         */
        public void setPlaylist(@NonNull Playlist playlist, boolean play) {
            setPlaylist(playlist, 0, play);
        }

        /**
         * 设置一个新的播放列表。
         *
         * @param playlist 播放列表（不能为 null）
         * @param position 播放列表中要播放的歌曲的位置
         * @param play     是否立即播放 {@code position} 参数指定处的音乐
         */
        public void setPlaylist(@NonNull Playlist playlist, int position, boolean play) {
            Preconditions.checkNotNull(playlist);
            if (!isConnected()) {
                return;
            }

            mPlaylistManager.setPlaylist(playlist, position, play);
        }

        public void getPlaylistAsync(@NonNull PlaylistManager.Callback callback) {
            Preconditions.checkNotNull(callback);
            mPlaylistManager.getPlaylistAsync(callback);
        }

        public void setNextPlay(@NonNull MusicItem musicItem) {
            Preconditions.checkNotNull(musicItem);
            if (!isConnected()) {
                return;
            }

            if (musicItem.equals(getPlayingMusicItem())) {
                return;
            }

            mPlaylistManager.insertMusicItem(getPlayingMusicItemPosition() + 1, musicItem);
        }

        public void moveMusicItem(int fromPosition, int toPosition) {
            if (!isConnected()) {
                return;
            }

            mPlaylistManager.moveMusicItem(fromPosition, toPosition);
        }

        public void removeMusicItem(@NonNull MusicItem musicItem) {
            Preconditions.checkNotNull(musicItem);
            if (!isConnected()) {
                return;
            }

            mPlaylistManager.removeMusicItem(musicItem);
        }

        /**
         * 获取播放列表的尺寸大小。
         */
        public int getPlaylistSize() {
            return mPlaylistManager.getPlaylistSize();
        }

        /**
         * 获取播放进度。
         *
         * @return 播放进度
         */
        public long getPlayProgress() {
            return mPlaylistStateHolder.mPlaylistState.getPlayProgress();
        }

        /**
         * 获取播放进度的更新时间。
         *
         * @return 播放进度的更新时间
         */
        public long getPlayProgressUpdateTime() {
            return mPlaylistStateHolder.mPlaylistState.getPlayProgressUpdateTime();
        }

        /**
         * 是否单曲循环播放。
         */
        public boolean isLooping() {
            return getPlayMode() == PlayMode.LOOP;
        }

        /**
         * 获取当前正在播放的音乐。
         *
         * @return 当前正在播放的音乐，如果当前没有任何播放的音乐，则返回 null
         */
        @Nullable
        public MusicItem getPlayingMusicItem() {
            return mPlaylistStateHolder.mPlaylistState.getMusicItem();
        }

        /**
         * 获取当前正在播放的音乐的持续时间。
         */
        public int getPlayingMusicItemDuration() {
            MusicItem musicItem = getPlayingMusicItem();
            if (musicItem == null) {
                return 0;
            }

            return musicItem.getDuration();
        }

        /**
         * 获取当前播放状态。
         *
         * @return 当前播放状态
         * @see snow.player.Player.PlaybackState
         */
        public PlaybackState getPlaybackState() {
            return mPlaylistStateHolder.mPlaylistState.getPlaybackState();
        }

        /**
         * 获取 audio session id。
         *
         * @return 如果 audio session id 不可用，则返回 0
         */
        public int getAudioSessionId() {
            return mPlaylistStateHolder.mPlaylistState.getAudioSessionId();
        }

        /**
         * 获取当前的缓存进度。
         *
         * @return 当前缓存进度，使用整数表示的百分比值，范围为 [0, 100]
         */
        public int getBufferingPercent() {
            return mPlaylistStateHolder.mPlaylistState.getBufferingPercent();
        }

        /**
         * 获取缓存进度更新时间。
         *
         * @return 缓存进度更新时间
         */
        public long getBufferingPercentUpdateTime() {
            return mPlaylistStateHolder.mPlaylistState.getBufferingPercentUpdateTime();
        }

        /**
         * 当前播放器是否处于 stalled 状态。
         * <p>
         * stalled 状态用于表示当前缓冲区是否有足够的数据继续播放，如果缓冲区没有足够的数据支撑继续播放，则该
         * 方法会返回 true，如果缓冲区有足够的数据可以继续播放，则返回 false。
         */
        public boolean isStalled() {
            return mPlaylistStateHolder.mPlaylistState.isStalled();
        }

        /**
         * 播放器是否发生了错误。
         */
        public boolean isError() {
            return getErrorCode() != Error.NO_ERROR;
        }

        /**
         * 获取错误码。
         *
         * @return 错误码。如果播放器没有发生错误，则返回 {@link snow.player.Player.Error#NO_ERROR}
         * @see snow.player.Player.Error
         */
        public int getErrorCode() {
            return mPlaylistStateHolder.mPlaylistState.getErrorCode();
        }

        /**
         * 获取错误信息。
         *
         * @return 错误信息。该方法的返回值只在错误发生时才有意义
         * @see #isError()
         * @see #getErrorCode()
         */
        public String getErrorMessage() {
            return mPlaylistStateHolder.mPlaylistState.getErrorMessage();
        }

        /**
         * 获取当前播放模式。
         *
         * @return 当前播放模式。
         */
        public PlayMode getPlayMode() {
            return mPlaylistStateHolder.mPlaylistState.getPlayMode();
        }

        /**
         * 获取当前播放列表的播放位置。
         *
         * @return 当前播放列表的播放位置
         */
        public int getPlayingMusicItemPosition() {
            return mPlaylistStateHolder.mPlaylistState.getPosition();
        }

        /**
         * 下一曲。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void skipToNext() {
            if (!mConnected) {
                return;
            }

            mDelegate.skipToNext();
        }

        /**
         * 上一曲。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void skipToPrevious() {
            if (!mConnected) {
                return;
            }

            mDelegate.skipToPrevious();
        }

        /**
         * 播放或暂停播放列表中指定索引处的音乐。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         *
         * @param position 目标位置。
         */
        @Override
        public void playOrPause(int position) {
            if (!mConnected) {
                return;
            }

            mDelegate.playOrPause(position);
        }

        /**
         * 设置播放模式。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         *
         * @param playMode 播放模式
         */
        @Override
        public void setPlayMode(@NonNull PlayMode playMode) {
            Preconditions.checkNotNull(playMode);
            if (!mConnected) {
                return;
            }

            mDelegate.setPlayMode(playMode);
        }

        /**
         * 开始播放。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void play() {
            if (!mConnected) {
                return;
            }

            mDelegate.play();
        }

        /**
         * 暂停播放。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void pause() {
            if (!mConnected) {
                return;
            }

            mDelegate.pause();
        }

        /**
         * 停止播放。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void stop() {
            if (!mConnected) {
                return;
            }

            mDelegate.stop();
        }

        /**
         * 播放/暂停。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void playOrPause() {
            if (!mConnected) {
                return;
            }

            mDelegate.playOrPause();
        }

        /**
         * 调整音乐播放进度。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         *
         * @param progress 要调整到的播放进度
         */
        @Override
        public void seekTo(int progress) {
            if (!mConnected) {
                return;
            }

            mDelegate.seekTo(progress);
        }

        /**
         * 快进。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void fastForward() {
            if (!mConnected) {
                return;
            }

            mDelegate.fastForward();
        }

        /**
         * 快退。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void rewind() {
            if (!mConnected) {
                return;
            }

            mDelegate.rewind();
        }

        @Override
        public void onNewPlaylist(int position, boolean play) {
            if (mConnected) {
                mDelegate.onNewPlaylist(position, play);
            }
        }

        @Override
        public void onMusicItemMoved(int fromPosition, int toPosition) {
            if (mConnected) {
                mDelegate.onMusicItemMoved(fromPosition, toPosition);
            }
        }

        @Override
        public void onMusicItemInserted(int position, MusicItem musicItem) {
            if (mConnected) {
                mDelegate.onMusicItemInserted(position, musicItem);
            }
        }

        @Override
        public void onMusicItemRemoved(MusicItem musicItem) {
            if (mConnected) {
                mDelegate.onMusicItemRemoved(musicItem);
            }
        }

        /**
         * 添加一个播放器播放状态监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 播放器播放状态监听器
         * @see Player.OnPlaybackStateChangeListener
         */
        public void addOnPlaybackStateChangeListener(Player.OnPlaybackStateChangeListener listener) {
            mPlaylistStateHolder.addOnPlaybackStateChangeListener(listener);
        }

        /**
         * 移除播放器播放状态监听器。
         *
         * @param listener 要移除的监听器。
         */
        public void removeOnPlaybackStateChangeListener(Player.OnPlaybackStateChangeListener listener) {
            mPlaylistStateHolder.removeOnPlaybackStateChangeListener(listener);
        }

        /**
         * 添加一个 stalled 状态监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器
         * @see Player.OnStalledChangeListener
         */
        public void addOnStalledChangeListener(OnStalledChangeListener listener) {
            mPlaylistStateHolder.addOnStalledChangeListener(listener);
        }

        /**
         * 移除 stalled 状态监听器。
         *
         * @param listener 要移除的监听器。
         */
        public void removeOnStalledChangeListener(OnStalledChangeListener listener) {
            mPlaylistStateHolder.removeOnStalledChangeListener(listener);
        }

        /**
         * 添加一个缓存进度监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器。
         * @see Player.OnBufferingPercentChangeListener
         */
        public void addOnBufferingPercentChangeListener(Player.OnBufferingPercentChangeListener listener) {
            mPlaylistStateHolder.addOnBufferingPercentChangeListener(listener);
        }

        /**
         * 移除缓存进度监听器。
         *
         * @param listener 要移除的监听器
         */
        public void removeOnBufferingPercentChangeListener(Player.OnBufferingPercentChangeListener listener) {
            mPlaylistStateHolder.removeOnBufferingPercentChangeListener(listener);
        }

        /**
         * 添加一个监听当前播放的 MusicItem 改变事件的监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器
         * @see Player.OnPlayingMusicItemChangeListener
         */
        public void addOnPlayingMusicItemChangeListener(Player.OnPlayingMusicItemChangeListener listener) {
            mPlaylistStateHolder.addOnPlayingMusicItemChangeListener(listener);
        }

        /**
         * 移除当前播放的 MusicItem 改变事件监听器
         *
         * @param listener 要移除的监听器
         */
        public void removeOnPlayingMusicItemChangeListener(Player.OnPlayingMusicItemChangeListener listener) {
            mPlaylistStateHolder.removeOnPlayingMusicItemChangeListener(listener);
        }

        /**
         * 添加一个用于监听播放器播放进度调整完毕事件的监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器
         * @see OnSeekListener
         */
        public void addOnSeekCompleteListener(OnSeekListener listener) {
            mPlaylistStateHolder.addOnSeekCompleteListener(listener);
        }

        /**
         * 移除用于监听播放器播放进度调整完毕的监听器。
         *
         * @param listener 要移除的监听器
         */
        public void removeOnSeekCompleteListener(OnSeekListener listener) {
            mPlaylistStateHolder.removeOnSeekCompleteListener(listener);
        }

        /**
         * 添加一个用于监听播放列表改变事件的监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器
         * @see PlaylistPlayer.OnPlaylistChangeListener
         */
        public void addOnPlaylistChangeListener(PlaylistPlayer.OnPlaylistChangeListener listener) {
            mPlaylistStateHolder.addOnPlaylistChangeListener(listener);
        }

        /**
         * 移除用于监听播放列表改变事件的监听器。
         *
         * @param listener 要移除的监听器
         */
        public void removeOnPlaylistChangeListener(PlaylistPlayer.OnPlaylistChangeListener listener) {
            mPlaylistStateHolder.removeOnPlaylistChangeListener(listener);
        }

        /**
         * 添加一个用于监听播放模式改变的监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器
         * @see PlaylistPlayer.OnPlayModeChangeListener
         */
        public void addOnPlayModeChangeListener(PlaylistPlayer.OnPlayModeChangeListener listener) {
            mPlaylistStateHolder.addOnPlayModeChangeListener(listener);
        }

        /**
         * 移除一个用于监听播放模式改变的监听器。
         *
         * @param listener 要移除的事件监听器
         */
        public void removeOnPlayModeChangeListener(PlaylistPlayer.OnPlayModeChangeListener listener) {
            mPlaylistStateHolder.removeOnPlayModeChangeListener(listener);
        }

        /**
         * 添加一个监听播放列表播放位置改变事件的监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器
         * @see PlaylistPlayer.OnPositionChangeListener
         */
        public void addOnPositionChangeListener(PlaylistPlayer.OnPositionChangeListener listener) {
            mPlaylistStateHolder.addOnPositionChangeListener(listener);
        }

        /**
         * 移除一个监听播放列表播放位置改变事件的监听器。
         *
         * @param listener 要移除的监听器
         */
        public void removeOnPositionChangeListener(PlaylistPlayer.OnPositionChangeListener listener) {
            mPlaylistStateHolder.removeOnPositionChangeListener(listener);
        }

        private static class PlaylistManagerImp extends PlaylistManager {
            protected PlaylistManagerImp(Context context, String playlistId) {
                super(context, playlistId);
            }

            @Override
            protected void setEditable(boolean editable) {
                super.setEditable(editable);
            }
        }
    }

    public static class RadioStationController implements RadioStationPlayer {
        private RadioStationPlayer mDelegate;
        private RadioStationStateHolder mRadioStationStateHolder;
        private boolean mConnected;

        RadioStationController() {
            mRadioStationStateHolder = new RadioStationStateHolder();
        }

        void setDelegate(RadioStationPlayer delegate) {
            mDelegate = delegate;
        }

        void setRadioStationState(RadioStationState radioStationState) {
            mRadioStationStateHolder.setRadioStationState(radioStationState);
        }

        RadioStationStateListener getRadioStationStateListener() {
            return mRadioStationStateHolder;
        }

        void setConnected(boolean connected) {
            mConnected = connected;
        }

        /**
         * 是否已连接到播放器。
         *
         * @return 如果播放器已连接，则返回 true，否则返回 false
         */
        public boolean isConnected() {
            return mConnected;
        }

        /**
         * 获取播放进度。
         *
         * @return 播放进度
         */
        public long getPlayProgress() {
            return mRadioStationStateHolder.mRadioStationState.getPlayProgress();
        }

        /**
         * 获取播放进度的更新时间。
         *
         * @return 播放进度的更新时间
         */
        public long getPlayProgressUpdateTime() {
            return mRadioStationStateHolder.mRadioStationState.getPlayProgressUpdateTime();
        }

        /**
         * 获取当前正在播放的音乐。
         *
         * @return 当前正在播放的音乐，如果当前没有任何播放的音乐，则返回 null
         */
        @Nullable
        public MusicItem getPlayingMusicItem() {
            return mRadioStationStateHolder.mRadioStationState.getMusicItem();
        }

        /**
         * 获取当前播放状态。
         *
         * @return 当前播放状态
         * @see snow.player.Player.PlaybackState
         */
        public PlaybackState getPlaybackState() {
            return mRadioStationStateHolder.mRadioStationState.getPlaybackState();
        }

        /**
         * 获取 audio session id。
         *
         * @return 如果 audio session id 不可用，则返回 0
         */
        public int getAudioSessionId() {
            return mRadioStationStateHolder.mRadioStationState.getAudioSessionId();
        }

        /**
         * 获取当前的缓存进度。
         *
         * @return 当前缓存进度，使用整数表示的百分比值，范围为 [0, 100]
         */
        public int getBufferingPercent() {
            return mRadioStationStateHolder.mRadioStationState.getBufferingPercent();
        }

        /**
         * 获取缓存进度更新时间。
         *
         * @return 缓存进度更新时间
         */
        public long getBufferingPercentUpdateTime() {
            return mRadioStationStateHolder.mRadioStationState.getBufferingPercentUpdateTime();
        }

        /**
         * 当前播放器是否处于 stalled 状态。
         * <p>
         * stalled 状态用于表示当前缓冲区是否有足够的数据继续播放，如果缓冲区没有足够的数据支撑继续播放，则该
         * 方法会返回 true，如果缓冲区有足够的数据可以继续播放，则返回 false。
         */
        public boolean isStalled() {
            return mRadioStationStateHolder.mRadioStationState.isStalled();
        }

        /**
         * 播放器是否发生了错误。
         */
        public boolean isError() {
            return getErrorCode() != Error.NO_ERROR;
        }

        /**
         * 获取错误码。
         *
         * @return 错误码。如果播放器没有发生错误，则返回 {@link snow.player.Player.Error#NO_ERROR}
         * @see snow.player.Player.Error
         */
        public int getErrorCode() {
            return mRadioStationStateHolder.mRadioStationState.getErrorCode();
        }

        /**
         * 获取错误信息。
         *
         * @return 错误信息。该方法的返回值只在错误发生时才有意义
         * @see #isError()
         * @see #getErrorCode()
         */
        public String getErrorMessage() {
            return mRadioStationStateHolder.mRadioStationState.getErrorMessage();
        }

        /**
         * 获取当前正在播放的 RadioStation 对象。
         */
        public RadioStation getRadioStation() {
            return mRadioStationStateHolder.mRadioStationState.getRadioStation();
        }

        /**
         * 下一曲。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void skipToNext() {
            if (!mConnected) {
                return;
            }

            mDelegate.skipToNext();
        }

        /**
         * 设置一个新的电台。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         *
         * @param radioStation 要播放的新电台
         */
        @Override
        public void setRadioStation(RadioStation radioStation) {
            if (!mConnected) {
                return;
            }

            mDelegate.setRadioStation(radioStation);
        }

        /**
         * 开始播放。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void play() {
            if (!mConnected) {
                return;
            }

            mDelegate.play();
        }

        /**
         * 暂停播放。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void pause() {
            if (!mConnected) {
                return;
            }

            mDelegate.pause();
        }

        /**
         * 停止播放。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void stop() {
            if (!mConnected) {
                return;
            }

            mDelegate.stop();
        }

        /**
         * 播放/暂停。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void playOrPause() {
            if (!mConnected) {
                return;
            }

            mDelegate.playOrPause();
        }

        /**
         * 调整音乐播放进度。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         *
         * @param progress 要调整到的播放进度
         */
        @Override
        public void seekTo(int progress) {
            if (!mConnected) {
                return;
            }

            mDelegate.seekTo(progress);
        }

        /**
         * 快进。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void fastForward() {
            if (!mConnected) {
                return;
            }

            mDelegate.fastForward();
        }

        /**
         * 快退。
         * <p>
         * 该方法只在连接到播放器后（{@link #isConnected()} 返回 true）才有效。
         */
        @Override
        public void rewind() {
            if (!mConnected) {
                return;
            }

            mDelegate.rewind();
        }

        /**
         * 添加一个播放器播放状态监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 播放器播放状态监听器
         * @see Player.OnPlaybackStateChangeListener
         */
        public void addOnPlaybackStateChangeListener(Player.OnPlaybackStateChangeListener listener) {
            mRadioStationStateHolder.addOnPlaybackStateChangeListener(listener);
        }

        /**
         * 移除播放器播放状态监听器。
         *
         * @param listener 要移除的监听器。
         */
        public void removeOnPlaybackStateChangeListener(Player.OnPlaybackStateChangeListener listener) {
            mRadioStationStateHolder.removeOnPlaybackStateChangeListener(listener);
        }

        /**
         * 添加一个 stalled 状态监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器
         * @see Player.OnStalledChangeListener
         */
        public void addOnStalledChangeListener(OnStalledChangeListener listener) {
            mRadioStationStateHolder.addOnStalledChangeListener(listener);
        }

        /**
         * 移除 stalled 状态监听器。
         *
         * @param listener 要移除的监听器。
         */
        public void removeOnStalledChangeListener(OnStalledChangeListener listener) {
            mRadioStationStateHolder.removeOnStalledChangeListener(listener);
        }

        /**
         * 添加一个缓存进度监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器。
         * @see Player.OnBufferingPercentChangeListener
         */
        public void addOnBufferingPercentChangeListener(Player.OnBufferingPercentChangeListener listener) {
            mRadioStationStateHolder.addOnBufferingPercentChangeListener(listener);
        }

        /**
         * 移除缓存进度监听器。
         *
         * @param listener 要移除的监听器
         */
        public void removeOnBufferingPercentChangeListener(Player.OnBufferingPercentChangeListener listener) {
            mRadioStationStateHolder.removeOnBufferingPercentChangeListener(listener);
        }

        /**
         * 添加一个监听当前播放的 MusicItem 改变事件的监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器
         * @see Player.OnPlayingMusicItemChangeListener
         */
        public void addOnPlayingMusicItemChangeListener(Player.OnPlayingMusicItemChangeListener listener) {
            mRadioStationStateHolder.addOnPlayingMusicItemChangeListener(listener);
        }

        /**
         * 移除当前播放的 MusicItem 改变事件监听器
         *
         * @param listener 要移除的监听器
         */
        public void removeOnPlayingMusicItemChangeListener(Player.OnPlayingMusicItemChangeListener listener) {
            mRadioStationStateHolder.removeOnPlayingMusicItemChangeListener(listener);
        }

        /**
         * 添加一个用于监听播放器播放进度调整完毕事件的监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器
         * @see OnSeekListener
         */
        public void addOnSeekCompleteListener(OnSeekListener listener) {
            mRadioStationStateHolder.addOnSeekCompleteListener(listener);
        }

        /**
         * 移除用于监听播放器播放进度调整完毕的监听器。
         *
         * @param listener 要移除的监听器
         */
        public void removeOnSeekCompleteListener(OnSeekListener listener) {
            mRadioStationStateHolder.removeOnSeekCompleteListener(listener);
        }

        /**
         * 添加一个用于监听器 RadioStation 改变事件的监听器。
         * <p>
         * 如果监听器已添加，则忽略本次调用。
         *
         * @param listener 要添加的监听器
         */
        public void addOnRadioStationChangeListener(RadioStationPlayer.OnRadioStationChangeListener listener) {
            mRadioStationStateHolder.addOnRadioStationChangeListener(listener);
        }

        /**
         * 移除一个用于监听器 RadioStation 改变事件的监听器。
         *
         * @param listener 要移除的监听器
         */
        public void removeOnRadioStationChangeListener(RadioStationPlayer.OnRadioStationChangeListener listener) {
            mRadioStationStateHolder.removeOnRadioStationChangeListener(listener);
        }
    }

    /**
     * 用于监听播放器连接状态的回调接口。
     */
    public interface OnConnectCallback {
        /**
         * 该方法会在连接成功或失败时调用。
         *
         * @param success 是否连接成功，如果为 true，则表示连接成功，否则为 false
         */
        void onConnected(boolean success);
    }

    private static class PlayerStateHolder implements PlayerStateListener {
        private PlayerState mPlayerState;
        private boolean mConnected;

        private List<Player.OnPlaybackStateChangeListener> mAllPlaybackStateChangeListener;
        private List<Player.OnStalledChangeListener> mAllStalledChangeListener;
        private List<Player.OnBufferingPercentChangeListener> mAllBufferingPercentChangeListener;
        private List<Player.OnPlayingMusicItemChangeListener> mAllPlayingMusicItemChangeListener;
        private List<Player.OnSeekListener> mAllSeekListener;

        PlayerStateHolder() {
            mAllPlaybackStateChangeListener = new ArrayList<>();
            mAllStalledChangeListener = new ArrayList<>();
            mAllBufferingPercentChangeListener = new ArrayList<>();
            mAllPlayingMusicItemChangeListener = new ArrayList<>();
            mAllSeekListener = new ArrayList<>();
        }

        void addOnPlaybackStateChangeListener(Player.OnPlaybackStateChangeListener listener) {
            if (mAllPlaybackStateChangeListener.contains(listener)) {
                return;
            }

            mAllPlaybackStateChangeListener.add(listener);
            notifyPlaybackStateChanged(listener);
        }

        void removeOnPlaybackStateChangeListener(Player.OnPlaybackStateChangeListener listener) {
            mAllPlaybackStateChangeListener.remove(listener);
        }

        void addOnStalledChangeListener(Player.OnStalledChangeListener listener) {
            if (mAllStalledChangeListener.contains(listener)) {
                return;
            }

            mAllStalledChangeListener.add(listener);
            notifyStalledChanged(listener);
        }

        void removeOnStalledChangeListener(Player.OnStalledChangeListener listener) {
            mAllStalledChangeListener.remove(listener);
        }

        void addOnBufferingPercentChangeListener(Player.OnBufferingPercentChangeListener listener) {
            if (mAllBufferingPercentChangeListener.contains(listener)) {
                return;
            }

            mAllBufferingPercentChangeListener.add(listener);
            notifyOnBufferingPercentChanged(listener);
        }

        void removeOnBufferingPercentChangeListener(Player.OnBufferingPercentChangeListener listener) {
            mAllBufferingPercentChangeListener.remove(listener);
        }

        void addOnPlayingMusicItemChangeListener(Player.OnPlayingMusicItemChangeListener listener) {
            if (mAllPlayingMusicItemChangeListener.contains(listener)) {
                return;
            }

            mAllPlayingMusicItemChangeListener.add(listener);
            notifyPlayingMusicItemChanged(listener);
        }

        void removeOnPlayingMusicItemChangeListener(Player.OnPlayingMusicItemChangeListener listener) {
            mAllPlayingMusicItemChangeListener.remove(listener);
        }

        void addOnSeekCompleteListener(Player.OnSeekListener listener) {
            if (mAllSeekListener.contains(listener)) {
                return;
            }

            mAllSeekListener.add(listener);
            notifySeekComplete(listener);
        }

        void removeOnSeekCompleteListener(Player.OnSeekListener listener) {
            mAllSeekListener.remove(listener);
        }

        void setPlayerState(PlayerState playerState) {
            mPlayerState = playerState;

            if (notConnect()) {
                return;
            }

            notifyPlayingMusicItemChanged();
            notifyPlaybackStateChanged();
            notifyOnBufferingPercentChanged();
        }

        boolean notConnect() {
            return !mConnected;
        }

        void setConnected(boolean connected) {
            mConnected = connected;
        }

        private void notifyPlaybackStateChanged(Player.OnPlaybackStateChangeListener listener) {
            if (notConnect()) {
                return;
            }

            switch (mPlayerState.getPlaybackState()) {
                case PREPARING:
                    listener.onPreparing();
                    break;
                case PREPARED:
                    listener.onPrepared(mPlayerState.getAudioSessionId());
                    break;
                case PLAYING:
                    listener.onPlay(mPlayerState.getPlayProgress(), mPlayerState.getPlayProgressUpdateTime());
                    break;
                case PAUSED:
                    listener.onPause();
                    break;
                case STOPPED:
                    listener.onStop();
                    break;
                case ERROR:
                    listener.onError(mPlayerState.getErrorCode(), mPlayerState.getErrorMessage());
                    break;
                default:
                    break;
            }
        }

        private void notifyPlaybackStateChanged() {
            if (notConnect()) {
                return;
            }

            for (Player.OnPlaybackStateChangeListener listener : mAllPlaybackStateChangeListener) {
                notifyPlaybackStateChanged(listener);
            }
        }

        private void notifyStalledChanged(Player.OnStalledChangeListener listener) {
            if (notConnect()) {
                return;
            }

            listener.onStalledChanged(mPlayerState.isStalled());
        }

        private void notifyStalledChanged() {
            if (notConnect()) {
                return;
            }

            for (Player.OnStalledChangeListener listener : mAllStalledChangeListener) {
                notifyStalledChanged(listener);
            }
        }

        private void notifyOnBufferingPercentChanged(Player.OnBufferingPercentChangeListener listener) {
            if (notConnect()) {
                return;
            }

            listener.onBufferingPercentChanged(mPlayerState.getBufferingPercent(),
                    mPlayerState.getBufferingPercentUpdateTime());
        }

        private void notifyOnBufferingPercentChanged() {
            if (notConnect()) {
                return;
            }

            for (Player.OnBufferingPercentChangeListener listener : mAllBufferingPercentChangeListener) {
                notifyOnBufferingPercentChanged(listener);
            }
        }

        private void notifyPlayingMusicItemChanged(Player.OnPlayingMusicItemChangeListener listener) {
            if (notConnect()) {
                return;
            }

            listener.onPlayingMusicItemChanged(mPlayerState.getMusicItem());
        }

        private void notifyPlayingMusicItemChanged() {
            if (notConnect()) {
                return;
            }

            for (Player.OnPlayingMusicItemChangeListener listener : mAllPlayingMusicItemChangeListener) {
                notifyPlayingMusicItemChanged(listener);
            }
        }

        private void notifySeeking(Player.OnSeekListener listener) {
            if (notConnect()) {
                return;
            }

            listener.onSeeking();
        }

        private void notifySeekComplete(Player.OnSeekListener listener) {
            if (notConnect()) {
                return;
            }

            listener.onSeekComplete(mPlayerState.getPlayProgress(), mPlayerState.getPlayProgressUpdateTime());
        }

        private void notifySeeking() {
            if (notConnect()) {
                return;
            }

            for (Player.OnSeekListener listener : mAllSeekListener) {
                notifySeeking(listener);
            }
        }

        private void notifySeekComplete() {
            if (notConnect()) {
                return;
            }

            for (Player.OnSeekListener listener : mAllSeekListener) {
                notifySeekComplete(listener);
            }
        }

        @Override
        public void onPreparing() {
            mPlayerState.setPlaybackState(Player.PlaybackState.PREPARING);

            notifyPlaybackStateChanged();
        }

        @Override
        public void onPrepared(int audioSessionId) {
            mPlayerState.setPlaybackState(Player.PlaybackState.PREPARED);

            notifyPlaybackStateChanged();
        }

        @Override
        public void onPlay(int playProgress, long playProgressUpdateTime) {
            mPlayerState.setPlaybackState(Player.PlaybackState.PLAYING);
            mPlayerState.setPlayProgress(playProgress);
            mPlayerState.setPlayProgressUpdateTime(playProgressUpdateTime);

            notifyPlaybackStateChanged();
        }

        @Override
        public void onPause() {
            mPlayerState.setPlaybackState(Player.PlaybackState.PAUSED);

            notifyPlaybackStateChanged();
        }

        @Override
        public void onStop() {
            mPlayerState.setPlaybackState(Player.PlaybackState.STOPPED);

            notifyPlaybackStateChanged();
        }

        @Override
        public void onError(int errorCode, String errorMessage) {
            mPlayerState.setPlaybackState(Player.PlaybackState.ERROR);
            mPlayerState.setErrorCode(errorCode);
            mPlayerState.setErrorMessage(errorMessage);

            notifyPlaybackStateChanged();
        }

        @Override
        public void onSeeking() {
            notifySeeking();
        }

        @Override
        public void onSeekComplete(int progress, long updateTime) {
            mPlayerState.setPlayProgress(progress);
            mPlayerState.setPlayProgressUpdateTime(updateTime);

            notifySeekComplete();
        }

        @Override
        public void onBufferingPercentChanged(int percent, long updateTime) {
            mPlayerState.setBufferingPercent(percent);
            mPlayerState.setBufferingPercentUpdateTime(updateTime);

            notifyOnBufferingPercentChanged();
        }

        @Override
        public void onPlayingMusicItemChanged(@Nullable MusicItem musicItem) {
            mPlayerState.setMusicItem(musicItem);

            notifyPlayingMusicItemChanged();
        }

        @Override
        public void onStalledChanged(boolean stalled) {
            mPlayerState.setStalled(stalled);

            notifyStalledChanged();
        }
    }

    private static class PlaylistStateHolder extends PlayerStateHolder implements PlaylistStateListener {
        private PlaylistManager mPlaylistManager;
        private PlaylistState mPlaylistState;

        private List<PlaylistPlayer.OnPlaylistChangeListener> mAllPlaylistChangeListener;
        private List<PlaylistPlayer.OnPlayModeChangeListener> mAllPlayModeChangeListener;
        private List<PlaylistPlayer.OnPositionChangeListener> mAllPositionChangeListener;

        PlaylistStateHolder(PlaylistManager playlistManager) {
            mPlaylistManager = playlistManager;

            mAllPlaylistChangeListener = new ArrayList<>();
            mAllPlayModeChangeListener = new ArrayList<>();
            mAllPositionChangeListener = new ArrayList<>();

            mPlaylistState = new PlaylistState();
        }

        void setPlaylistState(PlaylistState playlistState) {
            mPlaylistState = playlistState;

            notifyPlaylistChanged();
            notifyPlayModeChanged();
            super.setPlayerState(playlistState);
        }

        void addOnPlaylistChangeListener(PlaylistPlayer.OnPlaylistChangeListener listener) {
            if (mAllPlaylistChangeListener.contains(listener)) {
                return;
            }

            mAllPlaylistChangeListener.add(listener);
            notifyPlaylistChanged(listener);
        }

        void removeOnPlaylistChangeListener(PlaylistPlayer.OnPlaylistChangeListener listener) {
            mAllPlaylistChangeListener.remove(listener);
        }

        void addOnPlayModeChangeListener(PlaylistPlayer.OnPlayModeChangeListener listener) {
            if (mAllPlayModeChangeListener.contains(listener)) {
                return;
            }

            mAllPlayModeChangeListener.add(listener);
            notifyPlayModeChanged();
        }

        void removeOnPlayModeChangeListener(PlaylistPlayer.OnPlayModeChangeListener listener) {
            mAllPlayModeChangeListener.remove(listener);
        }

        void addOnPositionChangeListener(PlaylistPlayer.OnPositionChangeListener listener) {
            if (mAllPositionChangeListener.contains(listener)) {
                return;
            }

            mAllPositionChangeListener.add(listener);
            notifyPositionChanged(listener);
        }

        void removeOnPositionChangeListener(PlaylistPlayer.OnPositionChangeListener listener) {
            mAllPositionChangeListener.remove(listener);
        }

        private void notifyPlaylistChanged(PlaylistPlayer.OnPlaylistChangeListener listener) {
            if (notConnect()) {
                return;
            }

            listener.onPlaylistChanged(mPlaylistManager, mPlaylistState.getPosition());
        }

        private void notifyPlaylistChanged() {
            if (notConnect()) {
                return;
            }

            for (PlaylistPlayer.OnPlaylistChangeListener listener : mAllPlaylistChangeListener) {
                notifyPlaylistChanged(listener);
            }
        }

        private void notifyPlayModeChanged(PlaylistPlayer.OnPlayModeChangeListener listener) {
            if (notConnect()) {
                return;
            }

            listener.onPlayModeChanged(mPlaylistState.getPlayMode());
        }

        private void notifyPlayModeChanged() {
            if (notConnect()) {
                return;
            }

            for (PlaylistPlayer.OnPlayModeChangeListener listener : mAllPlayModeChangeListener) {
                notifyPlayModeChanged(listener);
            }
        }

        private void notifyPositionChanged(PlaylistPlayer.OnPositionChangeListener listener) {
            if (notConnect()) {
                return;
            }

            listener.onPositionChanged(mPlaylistState.getPosition());
        }

        private void notifyPositionChanged() {
            if (notConnect()) {
                return;
            }

            for (PlaylistPlayer.OnPositionChangeListener listener : mAllPositionChangeListener) {
                notifyPositionChanged(listener);
            }
        }

        @Override
        public void onPlaylistChanged(PlaylistManager playlistManager, int position) {
            mPlaylistState.setPosition(position);

            notifyPlaylistChanged();
        }

        @Override
        public void onPlayModeChanged(PlaylistPlayer.PlayMode playMode) {
            mPlaylistState.setPlayMode(playMode);

            notifyPlayModeChanged();
        }

        @Override
        public void onPositionChanged(int position) {
            mPlaylistState.setPosition(position);

            notifyPositionChanged();
        }
    }

    private static class RadioStationStateHolder extends PlayerStateHolder implements RadioStationStateListener {
        private RadioStationState mRadioStationState;

        private List<RadioStationPlayer.OnRadioStationChangeListener> mAllRadioStationChangeListener;

        RadioStationStateHolder() {
            mAllRadioStationChangeListener = new ArrayList<>();
            mRadioStationState = new RadioStationState();
        }

        void setRadioStationState(RadioStationState radioStationState) {
            mRadioStationState = radioStationState;

            notifyRadioStationChanged();
            super.setPlayerState(radioStationState);
        }

        void addOnRadioStationChangeListener(RadioStationPlayer.OnRadioStationChangeListener listener) {
            if (mAllRadioStationChangeListener.contains(listener)) {
                return;
            }

            mAllRadioStationChangeListener.add(listener);
            notifyRadioStationChanged(listener);
        }

        void removeOnRadioStationChangeListener(RadioStationPlayer.OnRadioStationChangeListener listener) {
            mAllRadioStationChangeListener.remove(listener);
        }

        private void notifyRadioStationChanged(RadioStationPlayer.OnRadioStationChangeListener listener) {
            if (notConnect()) {
                return;
            }

            listener.onRadioStationChanged(mRadioStationState.getRadioStation());
        }

        private void notifyRadioStationChanged() {
            if (notConnect()) {
                return;
            }

            for (RadioStationPlayer.OnRadioStationChangeListener listener : mAllRadioStationChangeListener) {
                notifyRadioStationChanged(listener);
            }
        }

        @Override
        public void onRadioStationChanged(RadioStation radioStation) {
            mRadioStationState.setRadioStation(radioStation);

            notifyRadioStationChanged();
        }
    }
}
