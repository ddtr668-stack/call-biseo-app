// Browser stand-in for the Android bridge, so the screens can be checked on a computer.
(function(){
  const now = Date.now(), H = 3600e3, D = 864e5;
  const t = n => { const d = new Date(now + n*D); return d.toISOString().slice(0,10); };
  let calls = [
    {id:'ms_1', uri:'content://m/1', file:'통화 녹음 김정훈 소장_x.m4a', contact:'김정훈 소장', number:'01012345678', ts: now - 1.2*H, dur: 312, status:'done',
     title:'11월 철근 납품 단가 재협의', category:'견적', company:'대성건설 평택현장', counterpart:'김정훈 소장', mood:'보통',
     summary:['평택 3공구 현장에 11월 셋째 주 HD13 철근 42톤 납품을 요청함.','톤당 98만 원 제시했으나 상대는 95만 원 이하를 원함.','목요일까지 수정 견적서를 메일로 보내기로 함.','납품일은 11월 18일 오전 8시, 현장 게이트 B로 확정.'],
     actions:[{id:'a1', type:'todo', title:'대성건설 수정 견적서 메일 발송', owner:'나', date:t(1), time:'', note:'톤당 96만 원 기준', done:false},
              {id:'a2', type:'event', title:'평택 3공구 철근 납품', owner:'함께', date:t(17), time:'08:00', note:'게이트 B', done:false, sent:{cal:'55'}},
              {id:'a3', type:'todo', title:'현장 출입 차량 번호 전달', owner:'상대', date:t(2), time:'', note:'', done:false}],
     keywords:['HD13','42톤','98만원','평택 3공구'],
     transcript:[{s:'상대',t:'네 이 과장님, 김정훈입니다.'},{s:'나',t:'소장님 안녕하세요, 지난번 말씀하신 11월 물량 건으로 전화드렸습니다.'},{s:'상대',t:'아 네. 셋째 주에 HD13으로 42톤 정도 들어와야 할 것 같아요.'},{s:'나',t:'네, 단가는 톤당 98만 원으로 생각하고 있습니다.'},{s:'상대',t:'에이, 요즘 시세가 있는데 95 밑으로는 맞춰 주셔야죠.'},{s:'나',t:'제가 회사랑 얘기해서 목요일까지 수정 견적 메일로 드리겠습니다.'}]},
    {id:'ms_2', uri:'content://m/2', file:'x', contact:'박미영 대리', ts: now - 5*H, dur: 95, status:'working'},
    {id:'ms_3', uri:'content://m/3', file:'x', contact:'010-9876-5432', ts: now - 26*H, dur: 431, status:'done',
     title:'형강 납기 지연 항의', category:'클레임', company:'한빛철강', mood:'부정',
     summary:['지난주 주문한 H형강 납기가 3일 늦어져 현장 작업이 멈췄다고 항의함.','내일 오전 중 출고 가능 여부를 확인해 회신하기로 함.'],
     actions:[{id:'b1', type:'todo', title:'한빛철강 H형강 출고 일정 확인 후 회신', owner:'나', date:t(-1), time:'10:00', note:'', done:false}],
     keywords:['H형강','납기 지연'], transcript:[{s:'상대',t:'아니 형강이 아직도 안 들어오면 어떡합니까.'},{s:'나',t:'정말 죄송합니다. 바로 확인해 보겠습니다.'}]},
    {id:'ms_4', uri:'content://m/4', file:'x', contact:'최영수 부장', ts: now - 3*D, dur: 640, status:'error', error:'AI 사용량 한도에 걸렸어요. 잠시 뒤 자동으로 다시 시도해요.'},
    {id:'ms_5', uri:'content://m/5', file:'x', contact:'김정훈 소장', ts: now - 6*D, dur: 188, status:'done', title:'10월 납품분 세금계산서 발행 확인', category:'수금', company:'대성건설 평택현장',
     summary:['10월 납품분 세금계산서를 이번 주 금요일까지 발행해 달라고 요청함.','결제는 다음 달 10일 현금 결제 예정.'], actions:[{id:'c1', type:'todo', title:'10월분 세금계산서 발행', owner:'나', date:t(-4), time:'', note:'', done:true}], keywords:['세금계산서'], transcript:[], star:true},
    {id:'ms_6', uri:'content://m/6', file:'x', contact:'통화 이정아 과장', ts: now - 8*D, dur: 75, status:'new'},
  ];
  if (location.hash === '#stress') for (let i=0;i<600;i++) calls.push({id:'ms_s'+i, uri:'content://m/s'+i, file:'x', contact:'통화 거래처'+(i%40), ts: now - i*3*H, dur: 60+i, status:'new'}); // STRESS
  let settings = {apiKey:'', model:'', auto:true, wifiOnly:false, popup:true, popupPos:'bottom', fontSize:'m', recPath:'Recordings/Call/,Call/', myInfo:'', calId:'', remindMin:'30', taskAccount:'', taskList:'', taskListName:''};
  let data = '';
  const later = (id, st, body, ms) => setTimeout(() => window.__native.resolve(id, st, body), ms || 300);
  let audio = {uri:'', playing:false, pos:0, dur:0, speed:1};
  window.Biseo = {
    version: () => '1.0.dev',
    loadData: () => data, saveData: j => { data = j; },
    calls: () => JSON.stringify(calls),
    patchCall: (id, j) => { const c = calls.find(x => x.id === id); if (c) Object.assign(c, JSON.parse(j)); return !!c; },
    removeCall: id => { calls = calls.filter(c => c.id !== id); setTimeout(() => window.__native.callsChanged(''), 50); },
    queue: j => { JSON.parse(j).forEach(id => { const c = calls.find(x => x.id === id); if (c) c.status = 'queued'; }); window.__native.callsChanged(''); },
    refresh: id => later(id, 200, '0'),
    workState: () => JSON.stringify({current:'ms_2', block: settings.apiKey ? '' : 'nokey', canRead:true}),
    pickAudio(){}, settings: () => JSON.stringify(settings), setSetting: (k, v) => { settings[k] = v; },
    aiModels: id => later(id, 200, JSON.stringify(['gemini-3.8-flash','gemini-3.6-flash','gemini-3.5-flash-lite'])),
    ai: (id, body) => { const q = JSON.parse(body).contents.slice(-1)[0].parts[0].text; const w = q.includes('날씨');
      later(id, 200, JSON.stringify({text: w ? '내일 청주는 맑고 낮 최고 17도, 아침 최저 4도예요. 일교차가 크니 겉옷 챙기세요.' : '이번 주에 하실 일은 2가지예요.\n- **대성건설 수정 견적서** 메일 발송 (내일까지) [ms_1]\n- 한빛철강 H형강 출고 일정 확인 후 회신 [ms_3]', sources: w ? [{title:'weather.go.kr', uri:'https://www.weather.go.kr'}] : []}), 900); },
    perms: () => JSON.stringify({audio:true, contacts:false, calendar:true, notif:true, battery:false, overlay:true}),
    askPerm(){}, openAppSettings(){},
    calendars: () => JSON.stringify([{id:3, name:'ddtr668@gmail.com', account:'ddtr668@gmail.com'},{id:5, name:'업무', account:'ddtr668@gmail.com'}]),
    addEvent: (id) => later(id, 200, '77'), openEvent(){}, eventExists: () => true,
    tasksConnect(){ settings.taskAccount = 'ddtr668@gmail.com'; window.__native.tasksChanged(); }, tasksDisconnect(){ settings.taskAccount=''; window.__native.tasksChanged(); },
    gRequest: (id, m, url) => later(id, 200, url.includes('/lists?') ? JSON.stringify({items:[{id:'L1', title:'내 할 일'},{id:'L2', title:'영업'}]}) : JSON.stringify({id:'T1'})),
    audioPlay: (u) => { audio = {uri:u, playing:true, pos:12000, dur:312000, speed:audio.speed}; return true; },
    audioPause: () => { audio.playing = false; }, audioSeek: ms => { audio.pos = ms; }, audioStop: () => { audio = {uri:'', playing:false, pos:0, dur:0, speed:1}; },
    audioSpeed: s => { audio.speed = +s; }, audioState: () => JSON.stringify(audio),
    shareText(){}, copyText(){ window.__native.toast('복사했어요'); }, openUrl(){}, saveFile(){}, importBackup: () => 0, takeLaunch: () => '', popupPreview: () => true
  };
})();
