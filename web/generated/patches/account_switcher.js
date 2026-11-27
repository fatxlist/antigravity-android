(function(){
/**
 * patches/account_switcher/install.js - Persistent Account Vault UI & instant
 * switcher for Settings -> Accounts and Settings -> Account.
 *
 * Uses the host app's native Settings React primitives ({ LU, PU, NU, OU, BI,
 * Dgb, Ew, eE }) injected by patch_main.py.
 */
var global = typeof window !== 'undefined' ? window : this;

if (!global.__agAccountSwitcher) {
  var lastSyncedSignature = '';
  var ACCOUNTS_EVENT = 'ag:accounts-updated';

  function getBridge() {
    return global.Android || global.AndroidBridge || null;
  }

  function getBridgeToken() {
    return String(global.__AG_NATIVE_BRIDGE_TOKEN__ || '');
  }

  function buildProfilePayload(userStatus, authContext) {
    var email = (userStatus && typeof userStatus.email === 'string') ? userStatus.email.trim() : '';
    var name = (userStatus && typeof userStatus.name === 'string') ? userStatus.name.trim() : '';
    var avatarUrl = (userStatus && typeof userStatus.profilePictureUrl === 'string')
      ? userStatus.profilePictureUrl.trim() : '';
    var tier = '';
    if (userStatus && userStatus.userTier) {
      tier = String(userStatus.userTier.description || userStatus.userTier.name || '').trim();
    }
    var projectId = (authContext && typeof authContext.project === 'string') ? authContext.project.trim() : '';
    var location = (authContext && typeof authContext.location === 'string') ? authContext.location.trim() : '';
    var isGcpTos = Boolean(authContext && authContext.isGcpTos);
    return {
      email: email,
      name: name,
      avatarUrl: avatarUrl,
      tier: tier,
      projectId: projectId,
      location: location,
      isGcpTos: isGcpTos
    };
  }

  function parseJsonArray(raw) {
    if (!raw) return [];
    try {
      var parsed = JSON.parse(String(raw));
      return Array.isArray(parsed) ? parsed : [];
    } catch (_) {
      return [];
    }
  }

  function readSavedAccounts() {
    var bridge = getBridge();
    if (!bridge || typeof bridge.listSavedAccounts !== 'function') return [];
    return parseJsonArray(bridge.listSavedAccounts(getBridgeToken()));
  }

  function emitAccountsUpdated() {
    try {
      if (typeof global.dispatchEvent === 'function' && typeof global.CustomEvent === 'function') {
        global.dispatchEvent(new global.CustomEvent(ACCOUNTS_EVENT));
      }
    } catch (_) {}
  }

  function syncProfile(userStatus, authContext) {
    var bridge = getBridge();
    if (!bridge || typeof bridge.syncCurrentAccount !== 'function') return [];
    var payload = buildProfilePayload(userStatus, authContext);
    var sig = JSON.stringify(payload);
    if (sig === lastSyncedSignature) {
      return readSavedAccounts();
    }
    lastSyncedSignature = sig;
    var updated = parseJsonArray(bridge.syncCurrentAccount(getBridgeToken(), sig));
    emitAccountsUpdated();
    return updated;
  }

  function initialLetter(acc) {
    var src = (acc && (acc.name || acc.email)) ? String(acc.name || acc.email).trim() : '?';
    return (src.charAt(0) || '?').toUpperCase();
  }

  function renderAvatar(React, Dgb, acc) {
    if (Dgb && acc && acc.avatarUrl) {
      return React.createElement(Dgb, { src: acc.avatarUrl, name: acc.name || acc.email, email: acc.email, size: 28 });
    }
    return React.createElement('div', {
      style: {
        width: '28px',
        height: '28px',
        borderRadius: '9999px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: '12px',
        fontWeight: 600,
        background: 'rgba(148,163,184,0.2)',
        flexShrink: 0
      }
    }, initialLetter(acc));
  }

  function runAccountSwitch(acc, deps, setBusyId, setErrMsg, refreshList) {
    var bridge = getBridge();
    if (!bridge || typeof bridge.switchSavedAccount !== 'function') {
      if (setErrMsg) setErrMsg('Native account bridge unavailable.');
      return;
    }
    if (setErrMsg) setErrMsg('');
    if (setBusyId) setBusyId(acc.id);
    lastSyncedSignature = '';

    var authContext = null;
    try {
      if (typeof deps.getStoreState === 'function') {
        var st = deps.getStoreState();
        authContext = st && st.auth ? st.auth.context : null;
      }
    } catch (_) {}
    var outgoing = buildProfilePayload(deps.userStatus, authContext);

    var res = {};
    try {
      res = JSON.parse(String(bridge.switchSavedAccount(getBridgeToken(), acc.id, JSON.stringify(outgoing)) || '{}'));
    } catch (_) {
      res = { ok: false, error: 'switch_parse_error' };
    }
    if (!res || !res.ok) {
      if (setBusyId) setBusyId('');
      if (setErrMsg) setErrMsg('Failed to switch account: ' + (res && res.error ? res.error : 'unknown error'));
      return;
    }
    if (refreshList) refreshList(Array.isArray(res.accounts) ? res.accounts : readSavedAccounts());

    Promise.resolve()
      .then(function () {
        if (deps.authService && typeof deps.authService.checkCurrentAuthStatus === 'function') {
          return deps.authService.checkCurrentAuthStatus();
        }
      })
      .then(function () {
        if (typeof deps.refreshUserStatus === 'function') {
          return deps.refreshUserStatus();
        }
      })
      .then(function () {
        if (setBusyId) setBusyId('');
        emitAccountsUpdated();
        if (typeof deps.getStoreState === 'function' && acc.email) {
          try {
            var postState = deps.getStoreState();
            var liveEmail = postState && postState.userStatus && postState.userStatus.email
              ? String(postState.userStatus.email).trim().toLowerCase()
              : '';
            if (liveEmail && liveEmail !== String(acc.email).trim().toLowerCase()) {
              if (typeof bridge.restartCoreForAccountSwitch === 'function') {
                bridge.restartCoreForAccountSwitch(getBridgeToken());
              }
            }
          } catch (_) {}
        }
      })
      .catch(function () {
        if (setBusyId) setBusyId('');
        if (typeof bridge.restartCoreForAccountSwitch === 'function') {
          bridge.restartCoreForAccountSwitch(getBridgeToken());
        }
      });
  }

  function AccountsListBody(props) {
    var deps = props.deps;
    var React = deps.React;
    var PU = deps.PU;
    var NU = deps.NU;
    var OU = deps.OU;
    var BI = deps.BI;
    var Dgb = deps.Dgb;
    var eE = deps.eE;

    var useState = React.useState;
    var useEffect = React.useEffect;

    var _listState = useState(function () { return readSavedAccounts(); });
    var accounts = _listState[0];
    var setAccounts = _listState[1];

    var _busyState = useState('');
    var busyId = _busyState[0];
    var setBusyId = _busyState[1];

    var _errState = useState('');
    var errMsg = _errState[0];
    var setErrMsg = _errState[1];

    useEffect(function () {
      if (deps.userStatus && deps.userStatus.email) {
        var authCtx = deps.authState && deps.authState.context ? deps.authState.context : null;
        setAccounts(syncProfile(deps.userStatus, authCtx));
      } else {
        setAccounts(readSavedAccounts());
      }
      function onUpdated() {
        setAccounts(readSavedAccounts());
      }
      if (typeof global.addEventListener === 'function') {
        global.addEventListener(ACCOUNTS_EVENT, onUpdated);
        return function () {
          if (typeof global.removeEventListener === 'function') {
            global.removeEventListener(ACCOUNTS_EVENT, onUpdated);
          }
        };
      }
    }, [deps.userStatus && deps.userStatus.email, deps.userStatus && deps.userStatus.userTier && deps.userStatus.userTier.description]);

    var isRc = Boolean(deps.isRemoteControl);

    var rows = accounts.map(function (acc) {
      var titleText = acc.name || acc.email || acc.id;
      var descParts = [];
      if (acc.email && acc.email !== titleText) descParts.push(acc.email);
      if (acc.tier) descParts.push(acc.tier);
      if (acc.projectId) descParts.push('Project: ' + acc.projectId);
      var descText = descParts.join(' · ') || 'Saved standalone session';

      var actions = [];
      if (acc.active) {
        actions.push(React.createElement('span', {
          key: 'active-badge',
          style: {
            fontSize: '11px',
            fontWeight: 600,
            padding: '2px 8px',
            borderRadius: '9999px',
            background: 'rgba(34,197,94,0.16)',
            color: '#4ade80'
          }
        }, 'Active'));
      } else {
        actions.push(React.createElement(BI, {
          key: 'switch-btn',
          variant: 'secondary',
          size: 'sm',
          disabled: isRc || Boolean(busyId),
          onClick: function () { runAccountSwitch(acc, deps, setBusyId, setErrMsg, setAccounts); }
        }, busyId === acc.id && eE ? React.createElement(eE, { size: 14 }) : (busyId === acc.id ? 'Switching...' : 'Switch')));
      }
      actions.push(React.createElement(BI, {
        key: 'rm-btn',
        variant: 'secondary',
        size: 'sm',
        disabled: isRc || Boolean(busyId),
        onClick: function () {
          var bridge = getBridge();
          if (bridge && typeof bridge.removeSavedAccount === 'function') {
            setAccounts(parseJsonArray(bridge.removeSavedAccount(getBridgeToken(), acc.id)));
            emitAccountsUpdated();
          }
        }
      }, 'Remove'));

      return React.createElement(OU, {
        key: acc.id,
        label: React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } },
          renderAvatar(React, Dgb, acc),
          React.createElement('div', { style: { fontWeight: 500, wordBreak: 'break-word' } }, titleText)
        ),
        description: descText,
        rightElement: React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: '6px', flexWrap: 'wrap' } }, actions)
      });
    });

    if (rows.length === 0) {
      rows.push(React.createElement(OU, {
        key: 'empty',
        label: 'No saved accounts yet',
        description: 'Sign in with a Google account and it will automatically appear here for instant switching.'
      }));
    }

    rows.push(React.createElement(OU, {
      key: 'add-account',
      label: 'Add Google account',
      description: isRc ? 'Sign in on the local device to add accounts.' : 'Open browser sign-in to add another account into the vault.',
      rightElement: React.createElement(BI, {
        variant: 'primary',
        size: 'sm',
        disabled: isRc || Boolean(busyId) || !(deps.authService && typeof deps.authService.showLoginFlow === 'function'),
        onClick: function () {
          if (typeof deps.closeSettings === 'function') deps.closeSettings();
          if (deps.authService && typeof deps.authService.showLoginFlow === 'function') {
            deps.authService.showLoginFlow();
          }
        }
      }, 'Add Account')
    }));

    if (errMsg) {
      rows.push(React.createElement(OU, {
        key: 'err',
        label: React.createElement('span', { style: { color: '#f87171' } }, errMsg)
      }));
    }

    return React.createElement(PU, { title: props.sectionTitle || 'Saved Accounts' },
      React.createElement(NU, null, rows)
    );
  }

  function renderSection(deps) {
    if (!deps || !deps.React || !deps.PU || !deps.NU || !deps.OU || !deps.BI) return null;
    return deps.React.createElement(AccountsListBody, { deps: deps, sectionTitle: 'Saved Accounts' });
  }

  function renderScreen(deps) {
    if (!deps || !deps.React || !deps.LU) return null;
    return deps.React.createElement(deps.LU, {
      title: 'Accounts',
      description: 'Switch between saved Antigravity accounts without re-authenticating.'
    }, renderSection(deps));
  }

  global.__agAccountSwitcher = {
    syncProfile: syncProfile,
    renderSection: renderSection,
    renderScreen: renderScreen,
    listAccounts: readSavedAccounts
  };
}


})();
