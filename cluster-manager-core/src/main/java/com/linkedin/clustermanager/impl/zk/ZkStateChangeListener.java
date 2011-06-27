package com.linkedin.clustermanager.impl.zk;

import org.I0Itec.zkclient.IZkStateListener;
import org.apache.zookeeper.Watcher.Event.KeeperState;

public class ZkStateChangeListener implements IZkStateListener {
	private volatile boolean _isConnected;
	private volatile boolean _hasSessionExpired;

	@Override
	public void handleNewSession() throws Exception {
		// TODO Auto-generated method stub
		//NOT SUPPORTED 
	}

	@Override
	public void handleStateChanged(KeeperState keeperState) throws Exception {
		switch (keeperState) {

		case SyncConnected:
			_isConnected = true;
			break;
		case Disconnected:
			_isConnected = false;
			break;
		case Expired:
			_isConnected = false;
			_hasSessionExpired = true;
			break;
		}
	}

	boolean isConnected() {
		return _isConnected;
	}

	boolean hasSessionExpired() {
		return _hasSessionExpired;
	}
}
