window.onload = () => {
    // --- Dynamic Background ---
    function setDynamicBackground() { const pcApiUrl = 'https://imageapi.hoshino2.top/pc/'; const mobileApiUrl = 'https://imageapi.hoshino2.top/mobile/'; const imageUrl = window.innerWidth <= 768 ? mobileApiUrl : pcApiUrl; const finalUrl = `${imageUrl}?time=${new Date().getTime()}`; document.body.style.backgroundImage = `url('${finalUrl}')`; }
    setDynamicBackground();
    let lastWindowWidth = window.innerWidth;
    let resizeTimer;
    window.addEventListener('resize', () => {
        const currentWidth = window.innerWidth;
        if (currentWidth !== lastWindowWidth) {
            lastWindowWidth = currentWidth;
            clearTimeout(resizeTimer);
            resizeTimer = setTimeout(setDynamicBackground, 250);
        }
    });

    // --- DOM Elements ---
    const playerNameInput = document.getElementById('player-name-input');
    const connectionControls = document.getElementById('connection-controls');
    const serverAddressInput = document.getElementById('server-address-input');
    const connectButton = document.getElementById('connect-button');
    const startGameButton = document.getElementById('start-game-button');
    const gameWrapper = document.getElementById('game-wrapper');
    const serverStatus = document.getElementById('server-status');
    const wordChoiceModal = document.getElementById('word-choice-modal');
    const wordOptionsContainer = document.getElementById('word-options');
    const serverInfoPanel = document.getElementById('server-info-panel');
    const infoOs = document.getElementById('info-os');
    const infoJava = document.getElementById('info-java');
    const infoLatency = document.getElementById('info-latency');
    const infoCpuText = document.getElementById('info-cpu-text');
    const infoCpuBar = document.getElementById('info-cpu-bar');
    const infoMemText = document.getElementById('info-mem-text');
    const infoMemBar = document.getElementById('info-mem-bar');
    const canvas = document.getElementById('canvas'), ctx = canvas.getContext('2d');
    const chatArea = document.getElementById('chat-area');
    const guessInput = document.getElementById('guess-input');
    const sendButton = document.getElementById('send-button');
    const clearButton = document.getElementById('clear-button');
    const statusDiv = document.getElementById('status');

    // --- State Variables ---
    let isDrawing = false, isMyTurn = false, lastX = 0, lastY = 0;
    let socket;
    let serverTestIntervalId = null;
    let latencyIntervalId = null;
    let playerCount = 0;
    let isHost = false;
    let hostName = '';

    // --- Server Testing Logic ---
    function debounce(func, delay) { let timeout; return function(...args) { clearTimeout(timeout); timeout = setTimeout(() => func.apply(this, args), delay); }; }
    function testServer(url) {
        if (!url || !url.startsWith('ws://') && !url.startsWith('wss://')) {
            serverStatus.textContent = '地址无效'; serverStatus.className = 'offline'; connectButton.disabled = true; return;
        }
        if (!serverStatus.classList.contains('online')) {
            serverStatus.textContent = '测试中...'; serverStatus.className = 'testing';
        }
        connectButton.disabled = true;
        try {
            const testSocket = new WebSocket(url);
            const startTime = Date.now();
            testSocket.onopen = () => {
                const latency = Date.now() - startTime;
                serverStatus.textContent = `服务器在线 ${latency}ms`;
                serverStatus.className = 'online';
                connectButton.disabled = false;
                testSocket.close();
            };
            testSocket.onerror = () => { serverStatus.textContent = '服务器离线'; serverStatus.className = 'offline'; connectButton.disabled = true; };
            setTimeout(() => {
                if (testSocket.readyState === WebSocket.CONNECTING) {
                    serverStatus.textContent = '连接超时'; serverStatus.className = 'offline'; connectButton.disabled = true; testSocket.close();
                }
            }, 3000);
        } catch (e) { serverStatus.textContent = '地址无效'; serverStatus.className = 'offline'; connectButton.disabled = true; }
    }
    function stopServerTesting() { if (serverTestIntervalId) { clearInterval(serverTestIntervalId); serverTestIntervalId = null; } }
    function startServerTesting() {
        stopServerTesting();
        testServer(serverAddressInput.value.trim());
        serverTestIntervalId = setInterval(() => {
            if (!connectionControls.classList.contains('hidden')) { testServer(serverAddressInput.value.trim()); }
            else { stopServerTesting(); }
        }, 3000);
    }
    const debouncedTestServer = debounce(testServer, 500);
    serverAddressInput.addEventListener('input', () => { debouncedTestServer(serverAddressInput.value.trim()); });
    
    // --- UI Update Logic ---
    function updateStatusText() {
        if (playerCount < 2) {
            if (isHost) {
                 statusDiv.textContent = `当前在线: ${playerCount}人，你是房主，等待其他玩家加入...`;
            } else {
                 statusDiv.textContent = `当前在线: ${playerCount}人，至少2人才能开始游戏`;
            }
            statusDiv.style.color = '#d9534f';
        } else {
            if (isHost) {
                statusDiv.textContent = `当前在线: ${playerCount}人，你是房主，请开始游戏`;
                statusDiv.style.color = '#28a745';
            } else {
                statusDiv.textContent = `当前在线: ${playerCount}人，等待房主 ${hostName} 开始游戏...`;
                statusDiv.style.color = '#28a745';
            }
        }
    }

    // 【新增】一个辅助函数，用于“静默”测试URL是否可连接，并返回一个Promise
    function validateUrl(url) {
        return new Promise(resolve => {
            if (!url || (!url.startsWith('ws://') && !url.startsWith('wss://'))) {
                return resolve(false);
            }
            try {
                const testSocket = new WebSocket(url);
                const timer = setTimeout(() => {
                    testSocket.close();
                    resolve(false);
                }, 2000); // 2秒超时
                testSocket.onopen = () => {
                    clearTimeout(timer);
                    testSocket.close();
                    resolve(true);
                };
                testSocket.onerror = () => {
                    clearTimeout(timer);
                    resolve(false);
                };
            } catch (e) {
                resolve(false);
            }
        });
    }

    // 【修改】重写整个初始化逻辑
    async function initializeConnectionDefaults() {
        playerNameInput.value = localStorage.getItem('drawGuessPlayerName') || '';
        connectButton.textContent = '进入游戏';

        let newDefaultUrl;
        try {
            const response = await fetch('/api/config');
            if (!response.ok) throw new Error('API fetch failed');
            const config = await response.json();
            newDefaultUrl = `ws://${window.location.hostname}:${config.ws_port}`;
        } catch (error) {
            console.error("无法加载服务器配置，将使用回退端口:", error);
            newDefaultUrl = `ws://${window.location.hostname}:12222`; // 回退值
        }

        const savedUrl = localStorage.getItem('drawGuessServerAddress');

        if (savedUrl) {
            const isSavedUrlStillValid = await validateUrl(savedUrl);
            if (isSavedUrlStillValid) {
                // 保存的URL仍然有效，使用它
                console.log("使用本地存储的有效服务器地址:", savedUrl);
                serverAddressInput.value = savedUrl;
            } else {
                // 保存的URL已失效，使用从服务器获取的新默认值
                console.warn("本地存储的服务器地址已失效，更新为服务器提供的新地址:", newDefaultUrl);
                serverAddressInput.value = newDefaultUrl;
                localStorage.setItem('drawGuessServerAddress', newDefaultUrl); // 更新本地存储
            }
        } else {
            // 本地没有保存任何URL，直接使用新获取的默认值
            console.log("本地无缓存，使用服务器提供的默认地址:", newDefaultUrl);
            serverAddressInput.value = newDefaultUrl;
        }

        // 最终，对输入框中现有的值启动连接测试
        startServerTesting();
    }
    
    // 页面加载后立即执行初始化
    initializeConnectionDefaults();

    // --- Connection Logic ---
    connectButton.addEventListener('click', () => {
        stopServerTesting();
        const playerName = playerNameInput.value.trim();
        if (!playerName) { alert('玩家名不能为空！'); startServerTesting(); return; }
        const serverUrl = serverAddressInput.value.trim();
        // 每次点击连接时，都保存当前的地址
        localStorage.setItem('drawGuessPlayerName', playerName);
        localStorage.setItem('drawGuessServerAddress', serverUrl); 
        playerNameInput.disabled = true;
        serverAddressInput.disabled = true;
        connectButton.disabled = true;
        connectButton.textContent = '进入中...';
        statusDiv.classList.remove('hidden');
        statusDiv.textContent = `正在连接到 ${serverUrl}...`;
        connect(serverUrl, playerName);
    });

    startGameButton.addEventListener('click', () => {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.send('START_GAME');
            startGameButton.classList.add('hidden');
        }
    });

    function connect(serverUrl, playerName) {
        socket = new WebSocket(serverUrl);
        socket.onopen = () => {
            statusDiv.textContent = '连接成功！等待认证...';
            latencyIntervalId = setInterval(() => {
                if (socket.readyState === WebSocket.OPEN) { socket.send(`PING:${Date.now()}`); }
            }, 2000);
        };
        socket.onmessage = (event) => {
            const message = event.data;
            if (message.startsWith('HOST_STATUS:')) {
                const parts = message.substring(12).split(':', 2);
                isHost = parts[0] === 'true';
                hostName = parts[1];
                if (isHost && playerCount >= 2) {
                    startGameButton.classList.remove('hidden');
                } else {
                    startGameButton.classList.add('hidden');
                }
                updateStatusText();
            } else if (message.startsWith('PONG:')) {
                const latency = Date.now() - parseInt(message.substring(5));
                infoLatency.textContent = `${latency}ms`;
            } else if (message.startsWith('SERVER_STATS:')) {
                const stats = JSON.parse(message.substring(13));
                infoOs.textContent = stats.os;
                infoJava.textContent = stats.java;
                infoCpuText.textContent = `${stats.cpu.toFixed(1)}%`;
                infoCpuBar.style.width = `${stats.cpu}%`;
                const memPercent = (stats.mem_used / stats.mem_total) * 100;
                infoMemText.textContent = `${stats.mem_used}MB`;
                infoMemBar.style.width = `${memPercent}%`;
            } else if (message.startsWith('CHOOSE_WORD:')) {
                startGameButton.classList.add('hidden');
                const words = message.substring(12).split(',');
                wordOptionsContainer.innerHTML = '';
                words.forEach(word => {
                    const button = document.createElement('button');
                    button.textContent = word;
                    button.onclick = () => { socket.send(`WORD_CHOSEN:${word}`); wordChoiceModal.classList.add('hidden'); };
                    wordOptionsContainer.appendChild(button);
                });
                wordChoiceModal.classList.remove('hidden');
            } else if (message.startsWith('AUTH_REQUEST:')) {
                connectionControls.classList.add('hidden');
                gameWrapper.classList.remove('hidden');
                serverInfoPanel.classList.remove('hidden');
                const token = message.substring(13);
                socket.send(`AUTH_VALIDATE:${token}:${playerName}`);
                statusDiv.textContent = '认证信息已发送...';
            } else if (message.startsWith('MESSAGE:')) {
                let rawMessage = message.substring(8);
                let content = rawMessage;
                if (rawMessage.includes('|PLAYERS:')) {
                    const parts = rawMessage.split('|PLAYERS:');
                    content = parts[0];
                    playerCount = parseInt(parts[1], 10);
                    updateStatusText();
                }
                if (content.includes('认证失败：该用户名已被使用')) {
                    alert('无法加入游戏：这个名字已经被别人用啦，请刷新页面换个名字试试！');
                    statusDiv.textContent = '用户名已被占用，请刷新重试。';
                } else if (content.includes('出题者已选好题目')) {
                    startGameButton.classList.add('hidden');
                    if (!isMyTurn) { statusDiv.textContent = '请猜测...'; statusDiv.style.color = '#d9534f'; }
                }
                const p = document.createElement('p'); p.textContent = content;
                if (content.includes('加入了游戏') || content.includes('离开了游戏') || content.includes('下一轮由') || content.includes('--------------------------------')) { p.className = 'system-message'; }
                else if (content.includes('猜对了')) { p.className = 'correct-guess'; }
                chatArea.appendChild(p); chatArea.scrollTop = chatArea.scrollHeight;
            } else if (message.startsWith('YOUR_TURN:')) {
                startGameButton.classList.add('hidden');
                isMyTurn = true;
                const word = message.substring(10);
                statusDiv.textContent = `轮到你画了！题目是: ${word}`;
                statusDiv.style.color = '#28a745';
            } else if (message.startsWith('DRAW:')) {
                const [x1, y1, x2, y2] = message.substring(5).split(',').map(Number);
                drawLine(x1, y1, x2, y2);
            } else if (message === 'CLEAR') {
                if (!isMyTurn) { statusDiv.textContent = '请猜测...'; statusDiv.style.color = '#d9534f'; }
                clearCanvas();
            }
        };
        socket.onclose = () => {
            if (latencyIntervalId) { clearInterval(latencyIntervalId); latencyIntervalId = null; }
            gameWrapper.classList.add('hidden');
            wordChoiceModal.classList.add('hidden');
            serverInfoPanel.classList.add('hidden');
            startGameButton.classList.add('hidden');
            connectionControls.classList.remove('hidden');
            if (!statusDiv.textContent.includes('用户名已被占用')) { statusDiv.textContent = '与服务器断开连接。'; }
            else { statusDiv.classList.add('hidden'); }
            isMyTurn = false;
            isHost = false;
            hostName = '';
            playerCount = 0;
            playerNameInput.disabled = false;
            serverAddressInput.disabled = false;
            connectButton.textContent = '进入游戏';
            startServerTesting();
        };
        socket.onerror = (error) => { console.error('WebSocket Error:', error); statusDiv.textContent = '连接错误！'; };
    }

    // --- Drawing and Message Sending Logic ---
    function drawLine(x1, y1, x2, y2) { ctx.beginPath(); ctx.strokeStyle = '#000'; ctx.lineWidth = 5; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke(); }
    function clearCanvas() { ctx.clearRect(0, 0, canvas.width, canvas.height); }
    
    function getMousePos(canvas, evt) {
        const rect = canvas.getBoundingClientRect();
        const scaleX = canvas.width / rect.width;
        const scaleY = canvas.height / rect.height;
        const clientX = evt.touches ? evt.touches[0].clientX : evt.clientX;
        const clientY = evt.touches ? evt.touches[0].clientY : evt.clientY;
        return { x: (clientX - rect.left) * scaleX, y: (clientY - rect.top) * scaleY };
    }

    function handleDrawStart(e) {
        if (!isMyTurn) return;
        e.preventDefault();
        isDrawing = true;
        const pos = getMousePos(canvas, e);
        [lastX, lastY] = [pos.x, pos.y];
    }

    function handleDrawMove(e) {
        if (!isDrawing || !isMyTurn) return;
        e.preventDefault();
        const pos = getMousePos(canvas, e);
        const newX = pos.x;
        const newY = pos.y;
        drawLine(lastX, lastY, newX, newY);
        socket.send(`DRAW:${lastX},${lastY},${newX},${newY}`);
        [lastX, lastY] = [newX, newY];
    }

    function handleDrawEnd(e) {
        e.preventDefault();
        isDrawing = false;
    }

    canvas.addEventListener('mousedown', handleDrawStart);
    canvas.addEventListener('mousemove', handleDrawMove);
    canvas.addEventListener('mouseup', handleDrawEnd);
    canvas.addEventListener('mouseout', handleDrawEnd);

    canvas.addEventListener('touchstart', handleDrawStart);
    canvas.addEventListener('touchmove', handleDrawMove);
    canvas.addEventListener('touchend', handleDrawEnd);
    canvas.addEventListener('touchcancel', handleDrawEnd);

    clearCanvas();
    function sendGuess() { const text = guessInput.value; if (socket && socket.readyState === WebSocket.OPEN && text && text.trim() !== '') { socket.send(`GUESS:${text}`); guessInput.value = ''; } }
    sendButton.addEventListener('click', sendGuess);
    guessInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); sendGuess(); } });
    clearButton.addEventListener('click', () => { if (isMyTurn && socket && socket.readyState === WebSocket.OPEN) { clearCanvas(); socket.send('CLEAR'); } else if (!isMyTurn){ alert('只有当前绘画者才能清空画板哦！'); } });
};