const express = require("express");
const crypto = require("crypto");
const nodemailer = require("nodemailer");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, Timestamp } = require("firebase-admin/firestore");

const router = express.Router();
const db = getFirestore();

if (!process.env.OTP_SECRET) {
  throw new Error("Missing OTP_SECRET in .env");
}

const transporter = nodemailer.createTransport({
  host: "smtp.gmail.com",
  port: 587,
  secure: false,
  auth: {
    user: process.env.EMAIL_USER,
    pass: process.env.EMAIL_PASS,
  },
});

const digest = (value) =>
  crypto
    .createHmac("sha256", process.env.OTP_SECRET)
    .update(value)
    .digest("hex");

const emailKey = (email) => digest(email.toLowerCase());

// Send password-reset OTP
router.post("/password-reset/send-otp", async (req, res) => {
  const email = String(req.body.email || "").trim().toLowerCase();

  // FIX: Correct email validation regex
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return res.status(400).json({
      message: "Enter a valid email address.",
    });
  }

  try {
    const ref = db.collection("passwordResetOtps").doc(emailKey(email));
    const previous = await ref.get();
    const now = Date.now();

    // Allow only one OTP request per minute
    if (
      previous.exists &&
      previous.data().lastSentAt &&
      now - previous.data().lastSentAt.toMillis() < 60_000
    ) {
      return res.status(429).json({
        message: "Please wait one minute before requesting another OTP.",
      });
    }

    let user;

    try {
      user = await getAuth().getUserByEmail(email);
    } catch (error) {
      if (error.code === "auth/user-not-found") {
        return res.json({
          message: "If an account exists, an OTP will be sent.",
        });
      }

      throw error;
    }

    const otp = crypto.randomInt(100000, 1000000).toString();

    await ref.set({
      uid: user.uid,
      otpHash: digest(`${email}:${otp}`),
      expiresAt: Timestamp.fromMillis(now + 5 * 60_000),
      lastSentAt: Timestamp.fromMillis(now),
      attempts: 0,
    });

    try {
      await transporter.sendMail({
        from: `"PadhAI" <${process.env.EMAIL_USER}>`,
        to: email,
        subject: "Your PadhAI password reset OTP",
        text:
          `Your PadhAI password reset OTP is ${otp}. ` +
          "It expires in five minutes. " +
          "If you did not request this, ignore this email.",
      });

      console.log("OTP email sent successfully to:", email);
    } catch (mailError) {
      await ref.delete();
      throw mailError;
    }

    return res.json({
      message: "If an account exists, an OTP will be sent.",
    });
  } catch (error) {
    console.error("Send OTP failed:", {
      code: error.code,
      message: error.message,
      command: error.command,
      responseCode: error.responseCode,
    });

    return res.status(500).json({
      message: "Unable to send OTP right now. Please try again.",
    });
  }
});

// Verify OTP and reset password in the same request
router.post("/password-reset/verify-otp", async (req, res) => {
  const email = String(req.body.email || "").trim().toLowerCase();
  const otp = String(req.body.otp || "").trim();
  const newPassword = String(req.body.newPassword || "");

  // FIX: Correct email validation regex
  if (
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ||
    !/^\d{6}$/.test(otp) ||
    newPassword.length < 8
  ) {
    return res.status(400).json({
      message:
        "Check your email, six-digit OTP, and password (minimum 8 characters).",
    });
  }

  const ref = db.collection("passwordResetOtps").doc(emailKey(email));

  try {
    const snapshot = await ref.get();

    if (!snapshot.exists) {
      return res.status(400).json({
        message: "OTP is invalid or expired. Request a new one.",
      });
    }

    const data = snapshot.data();

    if (data.expiresAt.toMillis() <= Date.now()) {
      await ref.delete();

      return res.status(400).json({
        message: "OTP is invalid or expired. Request a new one.",
      });
    }

    if (data.attempts >= 5) {
      await ref.delete();

      return res.status(429).json({
        message: "Too many incorrect attempts. Request a new OTP.",
      });
    }

    const expected = Buffer.from(digest(`${email}:${otp}`), "hex");
    const supplied = Buffer.from(data.otpHash, "hex");

    if (
      expected.length !== supplied.length ||
      !crypto.timingSafeEqual(expected, supplied)
    ) {
      await ref.update({
        attempts: data.attempts + 1,
      });

      return res.status(400).json({
        message: "Incorrect OTP.",
      });
    }

    // Confirm the account still matches the stored user
    const user = await getAuth().getUser(data.uid);

    if (!user.email || user.email.toLowerCase() !== email) {
      await ref.delete();

      return res.status(400).json({
        message: "OTP is invalid.",
      });
    }

    // Consume the OTP before changing the password
    await ref.delete();

    await getAuth().updateUser(data.uid, {
      password: newPassword,
    });

    await getAuth().revokeRefreshTokens(data.uid);

    return res.json({
      message: "Password reset successful. Please log in again.",
    });
  } catch (error) {
    console.error("Password reset failed:", error.message);

    return res.status(500).json({
      message: "Unable to reset password right now. Please try again.",
    });
  }
});

module.exports = router;