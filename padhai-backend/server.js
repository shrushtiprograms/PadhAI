const express = require("express");
const cors = require("cors");
const dotenv = require("dotenv");
const path = require("path");
const fs = require("fs");
const { initializeApp, cert } = require("firebase-admin/app");

dotenv.config();

const serviceAccountPath = path.join(
  __dirname,
  "firebase-service-account.json"
);

if (!process.env.EMAIL_USER || !process.env.EMAIL_PASS) {
  throw new Error("Missing EMAIL_USER or EMAIL_PASS in .env");
}

if (!fs.existsSync(serviceAccountPath)) {
  throw new Error("firebase-service-account.json not found");
}

const serviceAccount = require(serviceAccountPath);

initializeApp({
  credential: cert(serviceAccount),
});

const notesRouter = require("./src/routes/notes");
const authRouter = require("./src/routes/auth");

const app = express();

app.use(cors());
app.use(express.json({ limit: "15mb" }));
app.use((req, res, next) => {
  console.log("REQUEST:", req.method, req.url);
  next();
});

app.use("/api/notes", notesRouter);
app.use("/api/auth", authRouter);
app.use("/api/notes", require("./src/routes/mindmap"));

const port = process.env.PORT || 4000;

app.listen(port, "0.0.0.0", () => {
  console.log(`PadhAI backend listening on ${port}`);
});