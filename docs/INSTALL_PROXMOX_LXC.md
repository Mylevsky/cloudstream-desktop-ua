# Повний гайд зі встановлення Cloudstream Desktop UA на Proxmox VE (LXC)

Ця інструкція описує покроковий процес розгортання **Cloudstream Desktop UA** всередині **Debian LXC** контейнера на **Proxmox VE** із підключенням зовнішнього жорсткого диска для збереження медіафайлів у **Jellyfin**.

---

## 1. Підготовка сховища на хості Proxmox VE

Припустимо, ваш зовнішній HDD змонтований у системі Proxmox (наприклад, у `/mnt/pve/hdd` або `/data/Media`).

Перевірте наявність необхідних папок на хості Proxmox:
```bash
mkdir -p /data/Media/Movies
mkdir -p /data/Media/Shows
chmod -R 775 /data/Media
```

---

## 2. Створення Debian LXC контейнера

1. У веб-інтерфейсі Proxmox VE натисніть **Create CT**.
2. **General**:
   * Вкажіть **CT ID** (наприклад, `110`).
   * **Hostname**: `cloudstream`.
   * Зніміть галочку **Unprivileged container** (або залиште, якщо вмієте налаштовувати UID mapping, але для простоти роботи з дисками та Docker рекомендовано Privileged).
3. **Template**: оберіть `debian-12-standard` (або новіший).
4. **Disks**: кореневий диск контейнера (наприклад, 16–32 ГБ для системи та Docker-образів).
5. **CPU / Memory**:
   * Ядер: 2 або більше.
   * ОЗП: 2048 МБ (мінімум 1024 МБ).
6. **Network**: налаштуйте статичний IP або DHCP (наприклад, `192.168.0.50/24`).

### Важливо: Увімкнення підтримки Docker (Nesting)
Перед запуском контейнера:
1. Перейдіть у контейнер → **Options** → **Features**.
2. Поставте галочки:
   * **Nesting**: `Yes`
   * **keyctl**: `Yes`

### Прокидання зовнішнього HDD у контейнер
У терміналі хоста Proxmox виконайте (замініть `110` на ID вашого контейнера):
```bash
pct set 110 -mp0 /data/Media,mp=/data/Media
```
Тепер папки `/data/Media/Movies` та `/data/Media/Shows` будуть доступні всередині контейнера за тим самим шляхом `/data/Media`.

---

## 3. Встановлення Docker усередині LXC

Запустіть контейнер та зайдіть у його термінал (або через `pct enter 110`):

```bash
apt update && apt upgrade -y
apt install -y docker.io curl
systemctl enable --now docker
```

Перевірте статус Docker:
```bash
docker info
```

---

## 4. Запуск Cloudstream Desktop UA

Виконайте команду запуску контейнера:

```bash
docker run -d \
  --name cloudstream \
  --restart unless-stopped \
  --shm-size=1g \
  -p 6969:6969 \
  -e TZ=Europe/Kyiv \
  -e MOVIES_PATH=/data/Media/Movies \
  -e SHOWS_PATH=/data/Media/Shows \
  -v cs-config:/config \
  -v cs-data:/data \
  -v /data/Media/Movies:/data/Media/Movies \
  -v /data/Media/Shows:/data/Media/Shows \
  ghcr.io/mylevsky/cloudstream-desktop-ua:latest
```

### Пояснення параметрів:
| Параметр | Призначення |
| :--- | :--- |
| `-d` | Запуск у фоновому режимі |
| `--name cloudstream` | Назва контейнера |
| `--restart unless-stopped` | Автозапуск після рестарту сервера |
| `--shm-size=1g` | 1 ГБ спільної пам'яті для вбудованого Chromium |
| `-p 6969:6969` | Порт вебінтерфейсу |
| `-e TZ=Europe/Kyiv` | Часовий пояс (важливо для обходу захисту Cloudflare) |
| `-e MOVIES_PATH=...` | Папка, куди завантажувач кладе фільми |
| `-e SHOWS_PATH=...` | Папка, куди завантажувач кладе серіали |
| `-v /data/Media/...` | Монтування папок зовнішнього HDD з хоста Proxmox |
| `ghcr.io/mylevsky/...` | Ваш офіційний Docker-образ із підтримкою завантаження |

---

## 5. Перевірка роботи та перший вхід

1. Перевірте, що контейнер працює:
   ```bash
   docker ps
   ```
2. Перегляньте логи:
   ```bash
   docker logs --tail 50 cloudstream
   ```
   У логах з'явиться посилання для першого входу:
   ```text
   Listening on http://0.0.0.0:6969
   Sign-in link: http://127.0.0.1:6969/#claim=...
   ```
3. Відкрийте в браузері на комп'ютері або телефоні:
   `http://<IP_ВАШОГО_LXC>:6969`
   *(Замініть `127.0.0.1` у посиланні з логів на IP вашого LXC і перейдіть за ним для разової авторизації пристрою).*

---

## 6. Встановлення українських розширень (CakesTwix)

1. У вебінтерфейсі Cloudstream перейдіть у **Settings** (Налаштування) → **Plugins** (Плагіни).
2. Натисніть **Repositories** → **Add repository** (Додати репозиторій).
3. Введіть:
   * **URL:** `https://raw.githubusercontent.com/CakesTwix/cloudstream-extensions-uk/master/repo.json`  
     *(або просто шорткод: `cakes`)*
4. Натисніть **Add** та встановіть необхідні плагіни:
   * **Uakino** (фільми та серіали)
   * **Eneyida** (фільми та новинки)
   * **BambooUA** (серіали та аніме)
   * **UASerialsPro** (серіали)

---

## 7. Як користуватися завантажувачем для Jellyfin

1. Знайдіть у пошуку потрібний тайтл.
2. **Для фільму**:
   * Натисніть кнопку **Download** на сторінці фільму.
   * Файл збережеться за адресою:  
     `/data/Media/Movies/Назва Фільму (Рік)/Назва Фільму (Рік).mp4`
3. **Для серіалу**:
   * Оберіть потрібний сезон.
   * Щоб завантажити весь сезон одразу — натисніть **Download Season**.
   * Щоб завантажити одну серію — натисніть іконку **📥** біля серії.
   * Серії збережуться за адресою:  
     `/data/Media/Shows/Назва Серіалу/Season 01/Назва Серіалу - S01E01.mp4`
4. **Контроль завантажень**:
   * Перейдіть у розділ **Downloads** у лівому/нижньому меню.
   * Тут відображається черга, відсотки, поточна швидкість та ETA.
5. **Jellyfin**:
   * Оскільки файли потрапляють у папки, які вже підключені до Jellyfin, вони з'являться у вашому домашньому кінотеатрі автоматично.

---

## 8. Корисні команди обслуговування

* **Перезапуск контейнера:**
  ```bash
  docker restart cloudstream
  ```
* **Оновлення до найновішої версії вашого образу:**
  ```bash
  docker pull ghcr.io/mylevsky/cloudstream-desktop-ua:latest
  docker stop cloudstream && docker rm cloudstream
  # Після цього повторити команду docker run з кроку 4
  ```
* **Перегляд логів у реальному часі:**
  ```bash
  docker logs -f cloudstream
  ```
