# RunnerServer

A lightweight multithreaded HTTP server written in Java.  
Supports GET, POST, static file serving, logging, and a fixed thread pool.

## 🚀 Features
- Serves static files from the `www/` directory
- GET and POST request handling
- Logs requests to `server.log`
- Thread pool for handling multiple clients
- Simple echo endpoint: `/echo`
- Easy to run using `java RunnerServer`

## 📂 Project Structure
```

RunnerServer/
├── RunnerServer.java
├── www/
│    ├── index.html
│    └── echo.html
└── server.log (auto-created)

````

## ▶️ How to Run
Compile:
```
javac RunnerServer.java
````

Run:

```
java RunnerServer 8080 8
```

Open in browser:

```
http://localhost:8080/
```

## ✨ Echo Example

POST to:

```
/echo
```

You will see the parsed request body.

---

## 📌 Requirements

* Java 11+
* Any IDE (IntelliJ recommended)

---

## 📧 Author

Tarun Tripathi
GitHub: [https://github.com/tarun-tripathi](https://github.com/tarun-tripathi)

```
