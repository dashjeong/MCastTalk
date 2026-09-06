(() => {
  'use strict';

  function selectTab(platform) {
    const isAndroid = platform === 'android';
    document.getElementById('tabAndroid').classList.toggle('active', isAndroid);
    document.getElementById('tabIos').classList.toggle('active', !isAndroid);
    document.getElementById('panelAndroid').hidden = !isAndroid;
    document.getElementById('panelIos').hidden = isAndroid;
  }

  document.getElementById('tabAndroid').addEventListener('click', () => selectTab('android'));
  document.getElementById('tabIos').addEventListener('click', () => selectTab('ios'));

  const userAgent = navigator.userAgent || navigator.vendor || window.opera || '';
  selectTab(/iPad|iPhone|iPod/.test(userAgent) && !window.MSStream ? 'ios' : 'android');

  const launchButton = document.getElementById('launchSpeakerBtn');
  const httpsPort = document.body.dataset.httpsPort;
  if (!httpsPort) {
    launchButton.removeAttribute('href');
    launchButton.textContent = '보안 마이크 서버 준비 실패 · 방송 폰 확인';
    launchButton.setAttribute('aria-disabled', 'true');
    return;
  }
  launchButton.href =
    `https://${window.location.hostname}:${httpsPort}/speaker${window.location.hash || ''}`;
})();
