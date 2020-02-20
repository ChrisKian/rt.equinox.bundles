/*******************************************************************************
 * Copyright (c) 2014, 2016 Raymond Aug� and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Raymond Aug� <raymond.auge@liferay.com> - initial implementation
 ******************************************************************************/

package org.eclipse.equinox.http.servlet.tests;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.eclipse.equinox.http.servlet.context.ContextPathCustomizer;
import org.eclipse.equinox.http.servlet.tests.ServletTest.TestFilter;
import org.eclipse.equinox.http.servlet.tests.bundle.Activator;
import org.eclipse.equinox.http.servlet.tests.bundle.BundleAdvisor;
import org.eclipse.equinox.http.servlet.tests.bundle.BundleInstaller;
import org.eclipse.equinox.http.servlet.tests.util.BaseServlet;
import org.eclipse.equinox.http.servlet.tests.util.DispatchResultServlet;
import org.eclipse.equinox.http.servlet.tests.util.ServletRequestAdvisor;

import org.junit.Assert;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.context.ServletContextHelper;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

import junit.framework.TestCase;

public class DispatchingTest extends TestCase {

	@Override
	public void setUp() throws Exception {
		// Quiet logging for tests
		System.setProperty("/.LEVEL", "OFF");
		System.setProperty("org.eclipse.jetty.server.LEVEL", "OFF");
		System.setProperty("org.eclipse.jetty.servlet.LEVEL", "OFF");

		System.setProperty("org.osgi.service.http.port", "8090");
		BundleContext bundleContext = getBundleContext();
		installer = new BundleInstaller(ServletTest.TEST_BUNDLES_BINARY_DIRECTORY, bundleContext);
		advisor = new BundleAdvisor(bundleContext);
		String port = getPort();
		String contextPath = getContextPath();
		requestAdvisor = new ServletRequestAdvisor(port, contextPath);
		startBundles();
		stopJetty();
		startJetty();
	}

	@Override
	public void tearDown() throws Exception {
		for (ServiceRegistration<? extends Object> serviceRegistration : registrations) {
			serviceRegistration.unregister();
		}
		stopJetty();
		stopBundles();
		requestAdvisor = null;
		advisor = null;
		registrations.clear();
		try {
			installer.shutdown();
		} finally {
			installer = null;
		}
	}

	public void test_forwardDepth1() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/i4?u=5").forward(request, response);
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, new DispatchResultServlet(), props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("/a|/i4|u=5|/a/s2/i4|/s2|/a|/d|p=1|/a/s1/d|/s1", response);
	}

	public void test_forwardDepth1_WithRequestFilter() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/b?u=5").forward(request, response);
			}
		};

		Servlet servlet2 = new HttpServlet() {

			@Override
			protected void service(
				HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				PrintWriter writer = response.getWriter();

				writer.write(request.getContextPath());
				writer.write("|");
				writer.write(request.getPathInfo());
				writer.write("|");
				writer.write(request.getQueryString());
				writer.write("|");
				writer.write(request.getRequestURI());
				writer.write("|");
				writer.write(request.getServletPath());
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_PATH_INFO)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH)));
			}

		};

		TestFilter filter = new TestFilter() {

			@Override
			public void doFilter(
					ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {

				response.getWriter().write('b');

				super.doFilter(request, response, chain);

				response.getWriter().write('b');
			}

		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME, "F1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_DISPATCHER, new String[] {DispatcherType.REQUEST.toString()});
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN, "/*");
		registrations.add(getBundleContext().registerService(Filter.class, filter, props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("/a|/b|u=5&p=1|/a/s2/b|/s2|/a|/d|p=1|/a/s1/d|/s1", response);
		Assert.assertTrue(filter.getCalled());
	}

	public void test_forwardDepth1_WithRequestAndForwardFilter() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/b?u=5").forward(request, response);
			}
		};

		Servlet servlet2 = new HttpServlet() {

			@Override
			protected void service(
				HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				PrintWriter writer = response.getWriter();

				writer.write(request.getContextPath());
				writer.write("|");
				writer.write(request.getPathInfo());
				writer.write("|");
				writer.write(request.getQueryString());
				writer.write("|");
				writer.write(request.getRequestURI());
				writer.write("|");
				writer.write(request.getServletPath());
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_PATH_INFO)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH)));
			}

		};

		TestFilter filter = new TestFilter() {

			@Override
			public void doFilter(
					ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {

				response.getWriter().write('b');

				super.doFilter(request, response, chain);

				response.getWriter().write('b');
			}

		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME, "F1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_DISPATCHER, new String[] {DispatcherType.FORWARD.toString(),DispatcherType.REQUEST.toString()});
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN, "/*");
		registrations.add(getBundleContext().registerService(Filter.class, filter, props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("b/a|/b|u=5&p=1|/a/s2/b|/s2|/a|/d|p=1|/a/s1/d|/s1b", response);
		Assert.assertEquals(2, filter.getCount());
	}

	public void test_forwardDepth2() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/i2?p2=2").forward(request, response);
			}
		};

		Servlet servlet2 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s3/i3?p3=3").forward(request, response);
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "c1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/c1");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s3/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, new DispatchResultServlet(), props));

		String response = requestAdvisor.request("c1/s1/i1?p1=1");

		Assert.assertEquals("/c1|/i3|p3=3|/c1/s3/i3|/s3|/c1|/i1|p1=1|/c1/s1/i1|/s1", response);
	}

	public void test_forwardDepth3() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/i2?p2=2").forward(request, response);
			}
		};

		Servlet servlet2 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s3/i3?p3=3").forward(request, response);
			}
		};

		Servlet servlet3 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s4/i4?p4=4").forward(request, response);
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "c1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/c1");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s3/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet3, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s4/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, new DispatchResultServlet(), props));

		String response = requestAdvisor.request("c1/s1/i1?p1=1");

		Assert.assertEquals("/c1|/i4|p4=4|/c1/s4/i4|/s4|/c1|/i1|p1=1|/c1/s1/i1|/s1", response);
	}

	public void test_forwardNamedParameterAggregationAndPrecedence() throws Exception {
		Servlet sA = new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
				HttpServletRequest req, HttpServletResponse resp)
				throws ServletException, IOException {

				ServletContext servletContext = getServletContext();

				RequestDispatcher requestDispatcher = servletContext.getNamedDispatcher("s2");

				requestDispatcher.forward(req, resp);
			}
		};
		Servlet sB = new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
				HttpServletRequest req, HttpServletResponse resp)
				throws ServletException, IOException {

				PrintWriter writer = resp.getWriter();
				writer.write(String.valueOf(req.getQueryString()));
				writer.write("|");
				writer.write(String.valueOf(req.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING)));
				writer.write("|");
				writer.write(req.getParameter("p"));
				writer.write("|");
				writer.write(Arrays.toString(req.getParameterValues("p")));
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "c1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/c1");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "s1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		registrations.add(getBundleContext().registerService(Servlet.class, sA, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "s2");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		registrations.add(getBundleContext().registerService(Servlet.class, sB, props));

		String result = requestAdvisor.request("c1/s1/a?p=1&p=2");

		Assert.assertEquals("p=1&p=2|null|1|[1, 2]", result);
	}

	public void test_forwardNamed() throws Exception {
		Servlet sA = new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
				HttpServletRequest req, HttpServletResponse resp)
				throws ServletException, IOException {

				ServletContext servletContext = getServletContext();

				RequestDispatcher requestDispatcher = servletContext.getNamedDispatcher("s2");

				requestDispatcher.forward(req, resp);
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "c1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/c1");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "s1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		registrations.add(getBundleContext().registerService(Servlet.class, sA, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "s2");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		registrations.add(getBundleContext().registerService(Servlet.class, new DispatchResultServlet(), props));

		String result = requestAdvisor.request("c1/s1/a?p=1&p=2");

		Assert.assertEquals("/c1|/a|p=1&p=2|/c1/s1/a|/s1|null|null|null|null|null", result);
	}

	public void test_forwardParameterAggregationAndPrecedence() throws Exception {
		Servlet sA = new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
				HttpServletRequest req, HttpServletResponse resp)
				throws ServletException, IOException {

				RequestDispatcher requestDispatcher = req.getRequestDispatcher("/Servlet13B/a?p=3&p=4");

				requestDispatcher.forward(req, resp);
			}
		};
		Servlet sB = new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
				HttpServletRequest req, HttpServletResponse resp)
				throws ServletException, IOException {

				PrintWriter writer = resp.getWriter();
				writer.write(req.getQueryString());
				writer.write("|");
				writer.write((String)req.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING));
				writer.write("|");
				writer.write(req.getParameter("p"));
				writer.write("|");
				writer.write(Arrays.toString(req.getParameterValues("p")));
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "S13A");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/Servlet13A/*");
		registrations.add(getBundleContext().registerService(Servlet.class, sA, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "S13B");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/Servlet13B/*");
		registrations.add(getBundleContext().registerService(Servlet.class, sB, props));

		String result = requestAdvisor.request("Servlet13A/a?p=1&p=2");

		Assert.assertEquals("p=3&p=4&p=1&p=2|p=1&p=2|3|[3, 4, 1, 2]", result);
	}

	public void test_forwardStreamed() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/b?u=5").forward(request, response);
			}
		};

		Servlet servlet2 = new HttpServlet() {

			@Override
			protected void service(
				HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				ServletOutputStream outputStream = response.getOutputStream();

				write(outputStream, request.getContextPath());
				write(outputStream, "|");
				write(outputStream, request.getPathInfo());
				write(outputStream, "|");
				write(outputStream, request.getQueryString());
				write(outputStream, "|");
				write(outputStream, request.getRequestURI());
				write(outputStream, "|");
				write(outputStream, request.getServletPath());
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_PATH_INFO)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH)));
			}

		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("/a|/b|u=5&p=1|/a/s2/b|/s2|/a|/d|p=1|/a/s1/d|/s1", response);
	}

	public void test_forwardStreamed_WithRequestFilter() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/b?u=5").forward(request, response);
			}
		};

		Servlet servlet2 = new HttpServlet() {

			@Override
			protected void service(
				HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				ServletOutputStream outputStream = response.getOutputStream();

				write(outputStream, request.getContextPath());
				write(outputStream, "|");
				write(outputStream, request.getPathInfo());
				write(outputStream, "|");
				write(outputStream, request.getQueryString());
				write(outputStream, "|");
				write(outputStream, request.getRequestURI());
				write(outputStream, "|");
				write(outputStream, request.getServletPath());
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_PATH_INFO)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH)));
			}

		};

		TestFilter filter = new TestFilter() {

			@Override
			public void doFilter(
					ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {

				write(response.getOutputStream(), "b");

				super.doFilter(request, response, chain);

				write(response.getOutputStream(), "b");
			}

		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME, "F1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_DISPATCHER, new String[] {DispatcherType.REQUEST.toString()});
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN, "/*");
		registrations.add(getBundleContext().registerService(Filter.class, filter, props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("/a|/b|u=5&p=1|/a/s2/b|/s2|/a|/d|p=1|/a/s1/d|/s1", response);
		Assert.assertTrue(filter.getCalled());
	}

	public void test_forwardStreamed_WithRequestAndForwardFilter() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/b?u=5").forward(request, response);
			}
		};

		Servlet servlet2 = new HttpServlet() {

			@Override
			protected void service(
				HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				ServletOutputStream outputStream = response.getOutputStream();

				write(outputStream, request.getContextPath());
				write(outputStream, "|");
				write(outputStream, request.getPathInfo());
				write(outputStream, "|");
				write(outputStream, request.getQueryString());
				write(outputStream, "|");
				write(outputStream, request.getRequestURI());
				write(outputStream, "|");
				write(outputStream, request.getServletPath());
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_PATH_INFO)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH)));
			}

		};

		TestFilter filter = new TestFilter() {

			@Override
			public void doFilter(
					ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {

				write(response.getOutputStream(), "b");

				super.doFilter(request, response, chain);

				write(response.getOutputStream(), "b");
			}

		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME, "F1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_DISPATCHER, new String[] {DispatcherType.FORWARD.toString(),DispatcherType.REQUEST.toString()});
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN, "/*");
		registrations.add(getBundleContext().registerService(Filter.class, filter, props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("b/a|/b|u=5&p=1|/a/s2/b|/s2|/a|/d|p=1|/a/s1/d|/s1b", response);
		Assert.assertEquals(2, filter.getCount());
	}

	public void test_includeBasic() throws Exception {
		Servlet servlet8 = new HttpServlet() {

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				RequestDispatcher requestDispatcher =
					request.getRequestDispatcher("/S8/target");

				requestDispatcher.include(request, response);
			}

		};

		Servlet servlet8Target = new HttpServlet() {

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				response.getWriter().print("s8target");
			}

		};

		HttpService httpService = getHttpService();

		HttpContext httpContext = httpService.createDefaultHttpContext();

		httpService.registerServlet("/S8", servlet8, null, httpContext);
		httpService.registerServlet("/S8/target", servlet8Target, null, httpContext);

		Assert.assertEquals("s8target", requestAdvisor.request("S8"));
	}

	public void test_includeDepth1() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/b?u=5").include(request, response);
			}
		};

		Servlet servlet2 = new HttpServlet() {

			@Override
			protected void service(
				HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				PrintWriter writer = response.getWriter();

				writer.write(request.getContextPath());
				writer.write("|");
				writer.write(request.getPathInfo());
				writer.write("|");
				writer.write(request.getQueryString());
				writer.write("|");
				writer.write(request.getRequestURI());
				writer.write("|");
				writer.write(request.getServletPath());
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH)));
			}

		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("/a|/d|u=5&p=1|/a/s1/d|/s1|/a|/b|u=5|/a/s2/b|/s2", response);
	}

	public void test_includeDepth1_WithRequestFilter() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/b?u=5").include(request, response);
			}
		};

		Servlet servlet2 = new HttpServlet() {

			@Override
			protected void service(
				HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				PrintWriter writer = response.getWriter();

				writer.write(request.getContextPath());
				writer.write("|");
				writer.write(request.getPathInfo());
				writer.write("|");
				writer.write(request.getQueryString());
				writer.write("|");
				writer.write(request.getRequestURI());
				writer.write("|");
				writer.write(request.getServletPath());
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH)));
			}

		};

		TestFilter filter = new TestFilter() {

			@Override
			public void doFilter(
					ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {

				response.getWriter().write('b');

				super.doFilter(request, response, chain);

				response.getWriter().write('b');
			}

		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME, "F1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_DISPATCHER, new String[] {DispatcherType.REQUEST.toString()});
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN, "/*");
		registrations.add(getBundleContext().registerService(Filter.class, filter, props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("b/a|/d|u=5&p=1|/a/s1/d|/s1|/a|/b|u=5|/a/s2/b|/s2b", response);
		Assert.assertTrue(filter.getCalled());
	}

	public void test_includeDepth1_WithRequestAndIncludeFilter() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/b?u=5").include(request, response);
			}
		};

		Servlet servlet2 = new HttpServlet() {

			@Override
			protected void service(
				HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				PrintWriter writer = response.getWriter();

				writer.write(request.getContextPath());
				writer.write("|");
				writer.write(request.getPathInfo());
				writer.write("|");
				writer.write(request.getQueryString());
				writer.write("|");
				writer.write(request.getRequestURI());
				writer.write("|");
				writer.write(request.getServletPath());
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI)));
				writer.write("|");
				writer.write(String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH)));
			}

		};

		TestFilter filter = new TestFilter() {

			@Override
			public void doFilter(
					ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {

				response.getWriter().write('b');

				super.doFilter(request, response, chain);

				response.getWriter().write('b');
			}

		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, new DispatchResultServlet(), props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME, "F1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_DISPATCHER, new String[] {DispatcherType.INCLUDE.toString(), DispatcherType.REQUEST.toString()});
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN, "/*");
		registrations.add(getBundleContext().registerService(Filter.class, filter, props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("bb/a|/d|u=5&p=1|/a/s1/d|/s1|/a|/b|u=5|/a/s2/b|/s2bb", response);
		Assert.assertEquals(2, filter.getCount());
	}

	public void test_includeDepth2() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/i2?p2=2").include(request, response);
			}
		};

		Servlet servlet2 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s3/i3?p3=3").include(request, response);
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "c1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/c1");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s3/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, new DispatchResultServlet(), props));

		String response = requestAdvisor.request("c1/s1/i1?p1=1");

		Assert.assertEquals("/c1|/i1|p1=1|/c1/s1/i1|/s1|/c1|/i3|p3=3|/c1/s3/i3|/s3", response);
	}

	public void test_includeDepth3() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/i2?p2=2").include(request, response);
			}
		};

		Servlet servlet2 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s3/i3?p3=3").include(request, response);
			}
		};

		Servlet servlet3 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s4/i4?p4=4").include(request, response);
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "c1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/c1");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s3/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet3, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s4/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		registrations.add(getBundleContext().registerService(Servlet.class, new DispatchResultServlet(), props));

		String response = requestAdvisor.request("c1/s1/i1?p1=1");

		Assert.assertEquals("/c1|/i1|p1=1|/c1/s1/i1|/s1|/c1|/i4|p4=4|/c1/s4/i4|/s4", response);
	}

	public void test_includeNamedParameterAggregationAndPrecedence() throws Exception {
		Servlet sA = new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
				HttpServletRequest req, HttpServletResponse resp)
				throws ServletException, IOException {

				ServletContext servletContext = getServletContext();

				RequestDispatcher requestDispatcher = servletContext.getNamedDispatcher("s2");

				requestDispatcher.include(req, resp);
			}
		};
		Servlet sB = new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
				HttpServletRequest req, HttpServletResponse resp)
				throws ServletException, IOException {

				PrintWriter writer = resp.getWriter();
				writer.write(req.getQueryString());
				writer.write("|");
				writer.write(String.valueOf(req.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING)));
				writer.write("|");
				writer.write(req.getParameter("p"));
				writer.write("|");
				writer.write(Arrays.toString(req.getParameterValues("p")));
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "c1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/c1");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "s1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		registrations.add(getBundleContext().registerService(Servlet.class, sA, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "s2");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		registrations.add(getBundleContext().registerService(Servlet.class, sB, props));

		String result = requestAdvisor.request("c1/s1/a?p=1&p=2");

		Assert.assertEquals("p=1&p=2|null|1|[1, 2]", result);
	}

	public void test_includeNamed() throws Exception {
		Servlet sA = new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
				HttpServletRequest req, HttpServletResponse resp)
				throws ServletException, IOException {

				ServletContext servletContext = getServletContext();

				RequestDispatcher requestDispatcher = servletContext.getNamedDispatcher("s2");

				requestDispatcher.include(req, resp);
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "c1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/c1");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "s1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		registrations.add(getBundleContext().registerService(Servlet.class, sA, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "s2");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=c1)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		registrations.add(getBundleContext().registerService(Servlet.class, new DispatchResultServlet(), props));

		String result = requestAdvisor.request("c1/s1/a?p=1&p=2");

		Assert.assertEquals("/c1|/a|p=1&p=2|/c1/s1/a|/s1|null|null|null|null|null", result);
	}

	public void test_includeParameterAggregationAndPrecedence() throws Exception {
		Servlet sA = new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
				HttpServletRequest req, HttpServletResponse resp)
				throws ServletException, IOException {

				RequestDispatcher requestDispatcher = req.getRequestDispatcher("/Servlet13B/a?p=3&p=4");

				requestDispatcher.include(req, resp);
			}
		};
		Servlet sB = new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
				HttpServletRequest req, HttpServletResponse resp)
				throws ServletException, IOException {

				PrintWriter writer = resp.getWriter();
				writer.write(req.getQueryString());
				writer.write("|");
				writer.write((String)req.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING));
				writer.write("|");
				writer.write(req.getParameter("p"));
				writer.write("|");
				writer.write(Arrays.toString(req.getParameterValues("p")));
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "S13A");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/Servlet13A/*");
		registrations.add(getBundleContext().registerService(Servlet.class, sA, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "S13B");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/Servlet13B/*");
		registrations.add(getBundleContext().registerService(Servlet.class, sB, props));

		String result = requestAdvisor.request("Servlet13A/a?p=1&p=2");

		Assert.assertEquals("p=3&p=4&p=1&p=2|p=3&p=4|3|[3, 4, 1, 2]", result);
	}

	public void test_includeStreamed() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/b?u=5").include(request, response);
			}
		};

		Servlet servlet2 = new HttpServlet() {

			@Override
			protected void service(
				HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				ServletOutputStream outputStream = response.getOutputStream();

				write(outputStream, request.getContextPath());
				write(outputStream, "|");
				write(outputStream, request.getPathInfo());
				write(outputStream, "|");
				write(outputStream, request.getQueryString());
				write(outputStream, "|");
				write(outputStream, request.getRequestURI());
				write(outputStream, "|");
				write(outputStream, request.getServletPath());
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH)));
			}

		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("/a|/d|u=5&p=1|/a/s1/d|/s1|/a|/b|u=5|/a/s2/b|/s2", response);
	}

	public void test_includeStreamed_WithRequestFilter() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/b?u=5").include(request, response);
			}
		};

		Servlet servlet2 = new HttpServlet() {

			@Override
			protected void service(
				HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				ServletOutputStream outputStream = response.getOutputStream();

				write(outputStream, request.getContextPath());
				write(outputStream, "|");
				write(outputStream, request.getPathInfo());
				write(outputStream, "|");
				write(outputStream, request.getQueryString());
				write(outputStream, "|");
				write(outputStream, request.getRequestURI());
				write(outputStream, "|");
				write(outputStream, request.getServletPath());
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH)));
			}

		};

		TestFilter filter = new TestFilter() {

			@Override
			public void doFilter(
					ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {

				write(response.getOutputStream(), "b");

				super.doFilter(request, response, chain);

				write(response.getOutputStream(), "b");
			}

		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME, "F1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_DISPATCHER, new String[] {DispatcherType.REQUEST.toString()});
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN, "/*");
		registrations.add(getBundleContext().registerService(Filter.class, filter, props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("b/a|/d|u=5&p=1|/a/s1/d|/s1|/a|/b|u=5|/a/s2/b|/s2b", response);
		Assert.assertTrue(filter.getCalled());
	}

	public void test_includeStreamed_WithRequestAndIncludeFilter() throws Exception {
		Servlet servlet1 = new BaseServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {
				request.getRequestDispatcher("/s2/b?u=5").include(request, response);
			}
		};

		Servlet servlet2 = new HttpServlet() {

			@Override
			protected void service(
				HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {

				ServletOutputStream outputStream = response.getOutputStream();

				write(outputStream, request.getContextPath());
				write(outputStream, "|");
				write(outputStream, request.getPathInfo());
				write(outputStream, "|");
				write(outputStream, request.getQueryString());
				write(outputStream, "|");
				write(outputStream, request.getRequestURI());
				write(outputStream, "|");
				write(outputStream, request.getServletPath());
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI)));
				write(outputStream, "|");
				write(outputStream, String.valueOf(request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH)));
			}

		};

		TestFilter filter = new TestFilter() {

			@Override
			public void doFilter(
					ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {

				write(response.getOutputStream(), "b");

				super.doFilter(request, response, chain);

				write(response.getOutputStream(), "b");
			}

		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "a");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, "/a");
		registrations.add(getBundleContext().registerService(ServletContextHelper.class, new ServletContextHelper() {}, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s1/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet1, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/s2/*");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet2, props));

		props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=a)");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME, "F1");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_DISPATCHER, new String[] {DispatcherType.INCLUDE.toString(), DispatcherType.REQUEST.toString()});
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN, "/*");
		registrations.add(getBundleContext().registerService(Filter.class, filter, props));

		String response = requestAdvisor.request("a/s1/d?p=1");

		Assert.assertEquals("bb/a|/d|u=5&p=1|/a/s1/d|/s1|/a|/b|u=5|/a/s2/b|/s2bb", response);
		Assert.assertEquals(2, filter.getCount());
	}

	public void test_Bug479115() throws Exception {
		Servlet servlet = new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(
				final HttpServletRequest req, final HttpServletResponse resp)
				throws ServletException, IOException {

				final AtomicReference<String[]> results = new AtomicReference<String[]>();

				Thread thread = new Thread() {

					@Override
					public void run() {
						String[] parts = new String[2];

						parts[0] = req.getContextPath();
						parts[1] = req.getRequestURI();

						results.set(parts);
					}

				};

				thread.start();

				try {
					thread.join();
				}
				catch (InterruptedException ie) {
					throw new IOException(ie);
				}

				Assert.assertNotNull(results.get());

				PrintWriter writer = resp.getWriter();
				writer.write(results.get()[0]);
				writer.write("|");
				writer.write(results.get()[1]);
			}
		};

		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "Bug479115");
		props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/Bug479115/*");
		registrations.add(getBundleContext().registerService(Servlet.class, servlet, props));

		String result = requestAdvisor.request("Bug479115/a");

		Assert.assertEquals("|/Bug479115/a", result);
	}

	private String doRequest(String action, Map<String, String> params) throws IOException {
		return doRequestGetResponse(action, params).get("responseBody").get(0);
	}

	private Map<String, List<String>> doRequestGetResponse(String action, Map<String, String> params) throws IOException {
		StringBuilder requestInfo = new StringBuilder(ServletTest.PROTOTYPE);
		requestInfo.append(action);
		if (!params.isEmpty()) {
			boolean firstParam = true;
			for (Map.Entry<String, String> param : params.entrySet()) {
				if (firstParam) {
					requestInfo.append('?');
					firstParam = false;
				} else {
					requestInfo.append('&');
				}
				requestInfo.append(param.getKey());
				requestInfo.append('=');
				requestInfo.append(param.getValue());
			}
		}
		return requestAdvisor.request(requestInfo.toString(), null);
	}

	private BundleContext getBundleContext() {
		return Activator.getBundleContext();
	}

	private String getContextPath() {
		return getJettyProperty("context.path", "");
	}

	private HttpService getHttpService() {
		ServiceReference<HttpService> serviceReference = getBundleContext().getServiceReference(HttpService.class);
		return getBundleContext().getService(serviceReference);
	}

	private String getJettyProperty(String key, String defaultValue) {
		String qualifiedKey = ServletTest.JETTY_PROPERTY_PREFIX + key;
		String value = getProperty(qualifiedKey);
		if (value == null) {
			value = defaultValue;
		}
		return value;
	}

	private String getPort() {
		String defaultPort = getProperty(ServletTest.OSGI_HTTP_PORT_PROPERTY);
		if (defaultPort == null) {
			defaultPort = "80";
		}
		return getJettyProperty("port", defaultPort);
	}

	private String getProperty(String key) {
		BundleContext bundleContext = getBundleContext();
		String value = bundleContext.getProperty(key);
		return value;
	}

	private Bundle installBundle(String bundle) throws BundleException {
		return installer.installBundle(bundle);
	}

	private void startBundles() throws BundleException {
		for (String bundle : ServletTest.BUNDLES) {
			advisor.startBundle(bundle);
		}
	}

	private void startJetty() throws BundleException {
		advisor.startBundle(ServletTest.EQUINOX_JETTY_BUNDLE);
	}

	private void stopBundles() throws BundleException {
		for (int i = ServletTest.BUNDLES.length - 1; i >= 0; i--) {
			String bundle = ServletTest.BUNDLES[i];
			advisor.stopBundle(bundle);
		}
	}

	private void stopJetty() throws BundleException {
		advisor.stopBundle(ServletTest.EQUINOX_JETTY_BUNDLE);
	}

	private void uninstallBundle(Bundle bundle) throws BundleException {
		installer.uninstallBundle(bundle);
	}

	private void write(OutputStream outputStream, String string) throws IOException {
		outputStream.write(string.getBytes(StandardCharsets.UTF_8));
	}

	private static final String EQUINOX_DS_BUNDLE = "org.eclipse.equinox.ds";
	private static final String EQUINOX_JETTY_BUNDLE = "org.eclipse.equinox.http.jetty";
	private static final String JETTY_PROPERTY_PREFIX = "org.eclipse.equinox.http.jetty.";
	private static final String OSGI_HTTP_PORT_PROPERTY = "org.osgi.service.http.port";
	private static final String STATUS_OK = "OK";
	private static final String TEST_BUNDLES_BINARY_DIRECTORY = "/bundles_bin/";
	private static final String TEST_BUNDLE_1 = "tb1";

	private static final String[] BUNDLES = new String[] {
		ServletTest.EQUINOX_DS_BUNDLE
	};

	private BundleInstaller installer;
	private BundleAdvisor advisor;
	private ServletRequestAdvisor requestAdvisor;
	private final Collection<ServiceRegistration<? extends Object>> registrations = new ArrayList<ServiceRegistration<? extends Object>>();

	static class TestServletContextHelperFactory implements ServiceFactory<ServletContextHelper> {
		static class TestServletContextHelper extends ServletContextHelper {
			public TestServletContextHelper(Bundle bundle) {
				super(bundle);
			}};
		@Override
		public ServletContextHelper getService(Bundle bundle, ServiceRegistration<ServletContextHelper> registration) {
			return new TestServletContextHelper(bundle);
		}

		@Override
		public void ungetService(Bundle bundle, ServiceRegistration<ServletContextHelper> registration,
				ServletContextHelper service) {
			// nothing
		}

	}

	static class TestContextPathAdaptor extends ContextPathCustomizer {
		private final String defaultFilter;
		private final String contextPrefix;
		private final String testName;

		/**
		 * @param defaultFilter
		 * @param contextPrefix
		 */
		public TestContextPathAdaptor(String defaultFilter, String contextPrefix, String testName) {
			super();
			this.defaultFilter = defaultFilter;
			this.contextPrefix = contextPrefix;
			this.testName = testName;
		}

		@Override
		public String getDefaultContextSelectFilter(ServiceReference<?> httpWhiteBoardService) {
			if (testName.equals(httpWhiteBoardService.getProperty("servlet.init." + ServletTest.TEST_PATH_CUSTOMIZER_NAME))) {
				return defaultFilter;
			}
			return null;
		}

		@Override
		public String getContextPathPrefix(ServiceReference<ServletContextHelper> helper) {
			if (testName.equals(helper.getProperty(ServletTest.TEST_PATH_CUSTOMIZER_NAME))) {
				return contextPrefix;
			}
			return null;
		}

	}

	static class ErrorServlet extends HttpServlet{
		private static final long serialVersionUID = 1L;
		private final String errorCode;

		public ErrorServlet(String errorCode) {
			super();
			this.errorCode = errorCode;
		}

		@Override
		protected void service(
				HttpServletRequest request, HttpServletResponse response)
			throws ServletException ,IOException {

			if (response.isCommitted()) {
				System.out.println("Problem?");

				return;
			}

			PrintWriter writer = response.getWriter();

			String requestURI = (String)request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
			Integer status = (Integer)request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);

			writer.print(errorCode + " : " + status + " : ERROR : " + requestURI);
		}

	};
}